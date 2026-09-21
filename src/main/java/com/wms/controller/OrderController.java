package com.wms.controller;

import com.wms.model.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 统一出入库单查询(UNION ALL inbound_order + outbound_order)。
 * 供 Web 端"全部"类型下拉使用。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final JdbcTemplate jdbc;

    @Autowired
    public OrderController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 统一查询出入库单列表(分页)。
     * 参数与 InboundController / OutboundController 一致。
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> listAll(
            @RequestParam(required = false) String beginDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String createdBy,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        int ps = (pageSize <= 0 || pageSize > 200) ? 20 : pageSize;
        int p  = page <= 0 ? 1 : page;

        // 两个分支各持一套 WHERE + 一套参数。
        // 原来是一份 whereClause/args 被两边共用(靠 addAll 两遍),但关键字要匹配的字段
        // **两边不同** —— 入库侧供应商在行级(要 EXISTS 子查询)、出库侧客户是单头字段 ——
        // 共用一份参数必然错位,所以这里拆开,最后按 入库参数 + 出库参数 的顺序拼。
        List<Object> argsIn = new ArrayList<>();
        List<Object> argsOut = new ArrayList<>();
        StringBuilder whereIn = new StringBuilder();
        StringBuilder whereOut = new StringBuilder();
        appendCommonFilters(whereIn, argsIn, beginDate, endDate, createdBy, status);
        appendCommonFilters(whereOut, argsOut, beginDate, endDate, createdBy, status);

        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            // 入库:单头只有 单号/外部单号/备注,供应商在**行级** inbound_order_item.supplier
            // (inbound_order.supplier 是死列,从没被写过)。用 EXISTS 打行级,否则
            // 「按供应商搜」永远搜不到,而搜索框占位文案是承诺支持的。
            whereIn.append(" AND (order_no LIKE ? OR external_no LIKE ? OR remark LIKE ?"
                    + " OR EXISTS (SELECT 1 FROM inbound_order_item ii"
                    + "             WHERE ii.order_id = inbound_order.id AND ii.supplier LIKE ?))");
            argsIn.add(like); argsIn.add(like); argsIn.add(like); argsIn.add(like);
            // 出库:客户本来就是单头字段,直接匹配
            whereOut.append(" AND (order_no LIKE ? OR external_no LIKE ? OR remark LIKE ? OR customer LIKE ?)");
            argsOut.add(like); argsOut.add(like); argsOut.add(like); argsOut.add(like);
        }

        List<Object> branchArgs = new ArrayList<>(argsIn);
        branchArgs.addAll(argsOut);

        // COUNT
        String countSql = "SELECT COUNT(*) FROM ("
                + " SELECT id FROM inbound_order WHERE 1=1" + whereIn
                + " UNION ALL"
                + " SELECT id FROM outbound_order WHERE 1=1" + whereOut
                + ") t";
        Long total = jdbc.queryForObject(countSql, Long.class, branchArgs.toArray());
        if (total == null) total = 0L;

        // DATA — 用子查询 UNION ALL + ORDER BY + LIMIT/OFFSET
        //
        // partner(列表上「供应商 / 领用方」那一列)必须和 external_no 分开取:
        // 之前出库分支把 customer 别名成了 external_no,于是「外部单号」列显示的是领用方,
        // 前端又拿同一个值去填「合作方」列 —— 两列永远同值。
        //
        // 入库侧没有单头级的供应商(inbound_order.supplier 是死列,从没被写过),真相在
        // 行级的 inbound_order_item.supplier;一张单可能拆成多家供应商,所以去重后拼起来。
        String dataSql = "SELECT * FROM ("
                + " SELECT id, order_no, 'IN' AS type, external_no,"
                + "   (SELECT GROUP_CONCAT(DISTINCT ii.supplier ORDER BY ii.supplier SEPARATOR ' / ')"
                + "      FROM inbound_order_item ii"
                + "     WHERE ii.order_id = inbound_order.id"
                + "       AND ii.supplier IS NOT NULL AND ii.supplier <> '') AS partner,"
                + "   remark, created_by, created_at,"
                + "   item_count, total_quantity, status"
                + "   FROM inbound_order WHERE 1=1" + whereIn
                + " UNION ALL"
                + " SELECT id, order_no, 'OUT' AS type, external_no,"
                + "   customer AS partner,"
                + "   remark, created_by, created_at,"
                + "   item_count, total_quantity, status"
                + "   FROM outbound_order WHERE 1=1" + whereOut
                + ") t ORDER BY id DESC LIMIT ? OFFSET ?";
        List<Object> dataArgs = new ArrayList<>(branchArgs);
        int offset = (p - 1) * ps;
        dataArgs.add(ps);
        dataArgs.add(offset);

        List<Map<String, Object>> list = jdbc.queryForList(dataSql, dataArgs.toArray());

        return ApiResponse.ok(Map.of(
                "list", toCamelRows(list),
                "total", total,
                "page", p,
                "pageSize", ps
        ));
    }

    /**
     * 两个分支**一致**的那部分过滤条件(时间范围 / 创建人 / 状态)。
     *
     * 关键字故意不在这里 —— 它两侧匹配的字段不同(入库要打行级供应商),见 {@link #listAll}。
     * 抽出来是为了让「参数顺序」只有一处可错:每次都是「先公共、后关键字」。
     */
    private static void appendCommonFilters(StringBuilder where, List<Object> args,
                                            String beginDate, String endDate,
                                            String createdBy, String status) {
        if (beginDate != null && !beginDate.isBlank() && !"all".equalsIgnoreCase(beginDate)) {
            where.append(" AND created_at >= ?");
            args.add(beginDate + " 00:00:00");
        }
        if (endDate != null && !endDate.isBlank() && !"all".equalsIgnoreCase(endDate)) {
            where.append(" AND created_at <= ?");
            args.add(endDate + " 23:59:59");
        }
        if (createdBy != null && !createdBy.isBlank()) {
            where.append(" AND created_by = ?");
            args.add(createdBy);
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            where.append(" AND status = ?");
            args.add(status);
        }
    }

    /** queryForList 返回的是表列名的 snake_case,前端约定 camelCase,统一在这里转换 */
    private static List<Map<String, Object>> toCamelRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : row.entrySet()) {
                String k = e.getKey();
                StringBuilder sb = new StringBuilder(k.length());
                boolean upperNext = false;
                for (int i = 0; i < k.length(); i++) {
                    char c = k.charAt(i);
                    if (c == '_') { upperNext = true; continue; }
                    sb.append(upperNext ? Character.toUpperCase(c) : c);
                    upperNext = false;
                }
                converted.put(sb.toString(), e.getValue());
            }
            out.add(converted);
        }
        return out;
    }
}
