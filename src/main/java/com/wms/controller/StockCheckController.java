package com.wms.controller;

import com.wms.annotation.RequireRole;
import com.wms.model.ApiResponse;
import com.wms.model.StockCheck;
import com.wms.model.StockCheckItem;
import com.wms.service.StockCheckService;
import com.wms.util.Dates;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stock-checks")
public class StockCheckController {

    private final StockCheckService service;

    @Autowired
    public StockCheckController(StockCheckService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String beginDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int ps = (pageSize <= 0 || pageSize > 200) ? 20 : pageSize;
        int p  = page <= 0 ? 1 : page;
        var list = service.list(beginDate, endDate, status, p, ps);
        long total = service.count(beginDate, endDate, status);
        return ApiResponse.ok(Map.of(
                "list", list,
                "total", total,
                "page", p,
                "pageSize", ps
        ));
    }

    @GetMapping("/{id}")
    public ApiResponse<StockCheck> get(@PathVariable int id) {
        StockCheck c = service.get(id);
        if (c == null) throw new NotFoundException("盘点单不存在");
        return ApiResponse.ok(c);
    }

    /**
     * 创建盘点单(快照在库单元到明细)。
     * body: { scope, keyword, warehouseId, remark }
     *   keyword: SKU 模式=多个 SKU;LOCATION 模式=单个库位码;
     *            WAREHOUSE 模式=可选的库位码列表(逗号分隔,空=整个仓库)
     */
    @PostMapping
    @RequireRole("ADMIN")
    public ApiResponse<StockCheck> create(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String op = (String) req.getAttribute("username");
        if (op == null || op.isBlank()) op = "操作员";
        StockCheck c = service.create(
                asString(body == null ? null : body.get("scope")),
                asString(body == null ? null : body.get("keyword")),
                asInt(body == null ? null : body.get("warehouseId")),
                asString(body == null ? null : body.get("remark")),
                op);
        return ApiResponse.ok(c);
    }

    /** 填入某条明细的实盘数 */
    @PostMapping("/{id}/items/{itemId}/count")
    @RequireRole("ADMIN")
    public ApiResponse<Void> fillCount(@PathVariable int id,
                                       @PathVariable int itemId,
                                       @RequestBody Map<String, Object> body) {
        Object qObj = body == null ? null : body.get("countedQty");
        if (qObj == null) throw new IllegalArgumentException("缺少 countedQty");
        int qty;
        if (qObj instanceof Number) qty = ((Number) qObj).intValue();
        else qty = Integer.parseInt(qObj.toString());
        String remark = body == null ? null : (String) body.get("remark");
        service.fillCount(id, itemId, qty, remark);
        return ApiResponse.ok();
    }

    /** 删除某条明细行(仅 DRAFT);用于清理盘盈自动追加的多余行 */
    @DeleteMapping("/{id}/items/{itemId}")
    @RequireRole("ADMIN")
    public ApiResponse<Void> deleteItem(@PathVariable int id, @PathVariable int itemId) {
        service.deleteItem(id, itemId);
        return ApiResponse.ok();
    }

    /** 提交盘点 */
    @PostMapping("/{id}/commit")
    @RequireRole("ADMIN")
    public ApiResponse<StockCheck> commit(@PathVariable int id) {
        return ApiResponse.ok(service.commit(id));
    }

    /** 取消 */
    @PostMapping("/{id}/cancel")
    @RequireRole("ADMIN")
    public ApiResponse<Void> cancel(@PathVariable int id) {
        service.cancel(id);
        return ApiResponse.ok();
    }

    /**
     * PDA 扫一个码 → 定位本盘点单对应明细并记为已盘(幂等)。
     * body: { code: "<24 位编码>" }
     * 返回:完整 StockCheck(含 items)
     * 单外码 / 非在库(OUT/SCRAPPED 等)的码:自动追加一行记为盘盈(应盘 0 / 实盘 1)。
     *
     * 错误码:
     *   - 盘点单不存在 → 404;盘点单已 COMMITTED/CANCELLED → 409
     *   - 码不存在(inventory_item 无此码) → 404
     *   - 该码已盘过(含并发重复) → 422
     */
    @PostMapping("/{id}/scan")
    public ApiResponse<StockCheck> scanCode(@PathVariable int id, @RequestBody Map<String, Object> body) {
        String code = asString(body == null ? null : body.get("code"));
        if (code == null || code.isBlank()) throw new IllegalArgumentException("请提供 24 位码");
        return ApiResponse.ok(service.scanCodeToCheck(id, code));
    }

    /**
     * 导出差异报告 CSV。
     * filter: all=全部(盘盈行单独分区) / over=仅盘盈 / short=仅盘亏(未盘行计入盘亏)
     */
    @GetMapping(value = "/{id}/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv(@PathVariable int id,
                                            @RequestParam(defaultValue = "all") String filter) {
        StockCheck c = service.get(id);
        if (c == null) throw new NotFoundException("盘点单不存在");

        List<StockCheckItem> items = c.getItems() == null ? List.of() : c.getItems();
        List<StockCheckItem> over = new java.util.ArrayList<>();    // 盘盈
        List<StockCheckItem> normal = new java.util.ArrayList<>();  // 未盘/一致/盘亏
        for (StockCheckItem it : items) {
            if (STATE_OVER.equals(rowState(it))) over.add(it); else normal.add(it);
        }

        boolean onlyOver  = "over".equalsIgnoreCase(filter);
        boolean onlyShort = "short".equalsIgnoreCase(filter);

        StringBuilder sb = new StringBuilder();
        sb.append('﻿');
        sb.append("盘点单号,").append(csv(c.getCheckNo())).append('\n');
        sb.append("范围,").append(csv(scopeLabel(c.getScope()))).append('\n');
        sb.append("关键字,").append(csv(c.getScopeKeyword())).append('\n');
        sb.append("创建人,").append(csv(c.getCreatedBy())).append('\n');
        sb.append("备注,").append(csv(c.getRemark())).append('\n');
        sb.append("状态,").append(csv(statusLabel(c.getStatus()))).append('\n');
        sb.append("创建时间,").append(csv(Dates.fmt(c.getCreatedAt()))).append('\n');
        sb.append("提交时间,").append(csv(Dates.fmt(c.getCommittedAt()))).append('\n');
        sb.append("明细总数,").append(c.getTotalCount()).append('\n');
        sb.append("已盘数,").append(c.getCountedCount()).append('\n');
        sb.append("差异数,").append(c.getDiffCount()).append('\n');
        // 盘盈/盘亏件数:从明细现算(不落库、不加列)。口径:盘盈 = 实盘-账面 之和;
        // 盘亏 = 账面-实盘 之和,未盘行按实盘 0 计入 —— 与 filter=short 的口径一致。
        int overQty = 0, shortQty = 0;
        for (StockCheckItem it : over) {
            overQty += (it.getCountedQty() == null ? 0 : it.getCountedQty())
                     - (it.getSystemQty() == null ? 0 : it.getSystemQty());
        }
        for (StockCheckItem it : normal) {
            String st = rowState(it);
            if (STATE_SHORT.equals(st) || STATE_UNCOUNTED.equals(st)) {
                shortQty += (it.getSystemQty() == null ? 0 : it.getSystemQty())
                          - (it.getCountedQty() == null ? 0 : it.getCountedQty());
            }
        }
        sb.append("盘盈数(件),").append(overQty).append('\n');
        sb.append("盘亏数(件),").append(shortQty).append('\n');
        sb.append("导出范围,").append(csv(onlyOver ? "仅盘盈" : onlyShort ? "仅盘亏" : "全部")).append("\n\n");

        if (onlyOver) {
            sb.append(CSV_HEADER).append('\n');
            for (StockCheckItem it : over) appendRow(sb, it);
        } else if (onlyShort) {
            sb.append(CSV_HEADER).append('\n');
            for (StockCheckItem it : normal) {
                String st = rowState(it);
                // 未盘行按口径计入盘亏
                if (STATE_SHORT.equals(st) || STATE_UNCOUNTED.equals(st)) appendRow(sb, it);
            }
        } else {
            sb.append("盘点明细\n").append(CSV_HEADER).append('\n');
            for (StockCheckItem it : normal) appendRow(sb, it);
            if (!over.isEmpty()) {
                sb.append("\n盘盈明细\n").append(CSV_HEADER).append('\n');
                for (StockCheckItem it : over) appendRow(sb, it);
            }
        }

        String suffix = onlyOver ? "-盘盈" : onlyShort ? "-盘亏" : "";
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        String fname = URLEncoder.encode(
                (c.getCheckNo() == null ? "check_" + id : c.getCheckNo()) + suffix + ".csv",
                StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        h.setContentDispositionFormData("attachment", fname);
        return new ResponseEntity<>(body, h, 200);
    }

    /** 明细区表头:应盘数=系统账面,实盘数与差异未盘时留空 */
    private static final String CSV_HEADER = "24位编码,SKU,商品,库位,应盘数,实盘数,差异,盘点状态,行备注";

    private static final String STATE_OVER      = "盘盈";
    private static final String STATE_SHORT     = "盘亏";
    private static final String STATE_UNCOUNTED = "未盘";
    private static final String STATE_OK        = "一致";

    /** 行盘点状态:未盘 / 盘盈 / 盘亏 / 一致 */
    private static String rowState(StockCheckItem it) {
        Integer counted = it.getCountedQty();
        if (counted == null) return STATE_UNCOUNTED;
        int sys = it.getSystemQty() == null ? 0 : it.getSystemQty();
        if (counted > sys) return STATE_OVER;
        if (counted < sys) return STATE_SHORT;
        return STATE_OK;
    }

    private static void appendRow(StringBuilder sb, StockCheckItem it) {
        int sys = it.getSystemQty() == null ? 0 : it.getSystemQty();
        Integer counted = it.getCountedQty();
        sb.append(csv(it.getCode())).append(',')
          .append(csv(it.getSku())).append(',')
          .append(csv(it.getProductName())).append(',')
          .append(csv(it.getLocationCode())).append(',')
          .append(sys).append(',')
          .append(counted == null ? "" : counted.toString()).append(',')
          .append(counted == null ? "" : String.valueOf(counted - sys)).append(',')
          .append(rowState(it)).append(',')
          .append(csv(it.getRowRemark())).append('\n');
    }

    private static String scopeLabel(String s) {
        if (s == null) return "";
        switch (s) {
            case "ALL":       return "全部";
            case "SKU":       return "按 SKU";
            case "LOCATION":  return "按库位";
            case "WAREHOUSE": return "按仓库";
            default: return s;
        }
    }
    private static String statusLabel(String s) {
        if (s == null) return "";
        switch (s) {
            case "DRAFT":     return "草稿";
            case "COMMITTED": return "已提交";
            case "CANCELLED": return "已取消";
            default: return s;
        }
    }
    private static String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        String s = o.toString().trim();
        return s.isEmpty() ? null : Integer.valueOf(s);
    }
}
