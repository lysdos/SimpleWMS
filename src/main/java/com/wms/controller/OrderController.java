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

        // 构建公共 WHERE 条件
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder();
        if (beginDate != null && !beginDate.isBlank() && !"all".equalsIgnoreCase(beginDate)) {
            where.append(" AND created_at >= ?");
            args.add(beginDate + " 00:00:00");
        }
        if (endDate != null && !endDate.isBlank() && !"all".equalsIgnoreCase(endDate)) {
            where.append(" AND created_at <= ?");
            args.add(endDate + " 23:59:59");
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (order_no LIKE ? OR external_no LIKE ? OR remark LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if (createdBy != null && !createdBy.isBlank()) {
            where.append(" AND created_by = ?");
            args.add(createdBy);
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            where.append(" AND status = ?");
            args.add(status);
        }

        String whereClause = where.toString();

        // COUNT
        String countSql = "SELECT COUNT(*) FROM ("
                + " SELECT id FROM inbound_order WHERE 1=1" + whereClause
                + " UNION ALL"
                + " SELECT id FROM outbound_order WHERE 1=1" + whereClause
                + ") t";
        List<Object> countArgs = new ArrayList<>();
        countArgs.addAll(args);
        countArgs.addAll(args);
        Long total = jdbc.queryForObject(countSql, Long.class, countArgs.toArray());
        if (total == null) total = 0L;

        // DATA — 用子查询 UNION ALL + ORDER BY + LIMIT/OFFSET
        String dataSql = "SELECT * FROM ("
                + " SELECT id, order_no, 'IN' AS type, external_no, remark, created_by, created_at,"
                + "   item_count, total_quantity, status"
                + "   FROM inbound_order WHERE 1=1" + whereClause
                + " UNION ALL"
                + " SELECT id, order_no, 'OUT' AS type,"
                + "   customer AS external_no, remark, created_by, created_at,"
                + "   item_count, total_quantity, status"
                + "   FROM outbound_order WHERE 1=1" + whereClause
                + ") t ORDER BY id DESC LIMIT ? OFFSET ?";
        List<Object> dataArgs = new ArrayList<>();
        dataArgs.addAll(args);
        dataArgs.addAll(args);
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

    /** queryForList 返回 snake_case 列名,前端约定 camelCase;H2(MODE=MySQL,
     *  DATABASE_TO_LOWER=TRUE)连引号别名也会转小写,只能在 Java 侧统一转换 */
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
