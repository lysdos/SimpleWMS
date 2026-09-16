package com.wms.controller;

import com.wms.annotation.RequireRole;
import com.wms.model.ApiResponse;
import com.wms.model.StockCheck;
import com.wms.model.StockCheckItem;
import com.wms.service.StockCheckService;
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
        if (c == null) return ApiResponse.fail(404, "盘点单不存在");
        return ApiResponse.ok(c);
    }

    /**
     * 创建盘点单(快照在库单元到明细)。
     * body: { scope, keyword, remark }
     */
    @PostMapping
    @RequireRole("ADMIN")
    public ApiResponse<StockCheck> create(@RequestBody Map<String, String> body, HttpServletRequest req) {
        String op = (String) req.getAttribute("username");
        if (op == null || op.isBlank()) op = "操作员";
        StockCheck c = service.create(
                body == null ? null : body.get("scope"),
                body == null ? null : body.get("keyword"),
                body == null ? null : body.get("remark"),
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
     * PDA 扫一个码 → 校验 + 累加该盘点单对应明细的 counted_qty。
     * body: { code: "<24 位编码>" }
     * 返回:完整 StockCheck(含 items)
     *
     * 错误码:
     *   - 盘点单不存在 → 404
     *   - 盘点单已 COMMITTED/CANCELLED → 409
     *   - 码不存在 → 404
     *   - 码状态非 IN_STOCK → 409
     *   - 码不在本盘点单明细中 → 422
     */
    @PostMapping("/{id}/scan")
    public ApiResponse<StockCheck> scanCode(@PathVariable int id, @RequestBody Map<String, Object> body) {
        String code = asString(body == null ? null : body.get("code"));
        if (code == null || code.isBlank()) throw new IllegalArgumentException("请提供 24 位码");
        return ApiResponse.ok(service.scanCodeToCheck(id, code));
    }

    /** 导出差异报告 CSV(全部明细) */
    @GetMapping(value = "/{id}/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv(@PathVariable int id) {
        StockCheck c = service.get(id);
        if (c == null) throw new IllegalArgumentException("盘点单不存在");

        StringBuilder sb = new StringBuilder();
        sb.append('﻿');
        sb.append("盘点单号,").append(csv(c.getCheckNo())).append('\n');
        sb.append("范围,").append(csv(scopeLabel(c.getScope()))).append('\n');
        sb.append("关键字,").append(csv(c.getScopeKeyword())).append('\n');
        sb.append("创建人,").append(csv(c.getCreatedBy())).append('\n');
        sb.append("备注,").append(csv(c.getRemark())).append('\n');
        sb.append("状态,").append(csv(statusLabel(c.getStatus()))).append('\n');
        sb.append("创建时间,").append(csv(c.getCreatedAt() == null ? "" : c.getCreatedAt().toString())).append('\n');
        sb.append("提交时间,").append(csv(c.getCommittedAt() == null ? "" : c.getCommittedAt().toString())).append('\n');
        sb.append("明细总数,").append(c.getTotalCount()).append('\n');
        sb.append("已盘数,").append(c.getCountedCount()).append('\n');
        sb.append("差异数,").append(c.getDiffCount()).append("\n\n");
        sb.append("24位编码,SKU,商品,库位,系统数,实盘数,差异,行备注\n");
        for (StockCheckItem it : c.getItems()) {
            sb.append(csv(it.getCode())).append(',')
              .append(csv(it.getSku())).append(',')
              .append(csv(it.getProductName())).append(',')
              .append(csv(it.getLocationCode())).append(',')
              .append(it.getSystemQty() == null ? 0 : it.getSystemQty()).append(',')
              .append(it.getCountedQty() == null ? "" : it.getCountedQty().toString()).append(',')
              .append(it.getDiff() == null ? "" : it.getDiff().toString()).append(',')
              .append(csv(it.getRowRemark())).append('\n');
        }

        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        String fname = URLEncoder.encode(
                (c.getCheckNo() == null ? "check_" + id : c.getCheckNo()) + ".csv",
                StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        h.setContentDispositionFormData("attachment", fname);
        return new ResponseEntity<>(body, h, 200);
    }

    private static String scopeLabel(String s) {
        if (s == null) return "";
        switch (s) {
            case "ALL":      return "全部";
            case "SKU":      return "按 SKU";
            case "LOCATION": return "按库位";
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
}
