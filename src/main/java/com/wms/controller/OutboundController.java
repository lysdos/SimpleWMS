package com.wms.controller;

import com.wms.model.ApiResponse;
import com.wms.model.InventoryLog;
import com.wms.model.OutboundOrder;
import com.wms.model.OutboundOrderItem;
import com.wms.service.OutboundService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/outbound")
public class OutboundController {

    private final OutboundService outboundService;

    @Autowired
    public OutboundController(OutboundService outboundService) {
        this.outboundService = outboundService;
    }

    /** 老接口:单货出库(兼容,内部走 DRAFT 路径) */
    @PostMapping
    public ApiResponse<OutboundOrder> outbound(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Integer productId = asInt(body.get("productId"));
        Integer quantity  = asInt(body.get("quantity"));
        String remark    = asString(body.get("remark"));
        String operator  = currentOperator(req);

        if (productId == null) throw new IllegalArgumentException("请选择商品");
        if (quantity == null)  throw new IllegalArgumentException("请输入出库数量");
        if (quantity <= 0)     throw new IllegalArgumentException("出库数量必须大于 0");

        return ApiResponse.ok(outboundService.doOutbound(productId, quantity, remark, operator));
    }

    /**
     * 新接口:批量出库
     * 入参:{ customer, externalNo, remark, items:[{productId,quantity,rowRemark}] }
     * 返回:完整出库单(含明细)
     */
    @PostMapping("/batch")
    public ApiResponse<OutboundOrder> outboundBatch(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String operator = currentOperator(req);
        String customer   = asString(body.get("customer"));
        String externalNo = asString(body.get("externalNo"));
        String orderRemark = asString(body.get("remark"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawItems = (List<Map<String, Object>>) body.get("items");
        if (rawItems == null || rawItems.isEmpty()) {
            throw new IllegalArgumentException("请至少添加一条出库明细");
        }
        List<OutboundService.BatchItem> items = new ArrayList<>();
        for (Map<String, Object> r : rawItems) {
            OutboundService.BatchItem it = new OutboundService.BatchItem();
            it.productId  = asInt(r.get("productId"));
            it.quantity   = asInt(r.get("quantity"));
            it.rowRemark  = asString(r.get("rowRemark"));
            items.add(it);
        }
        OutboundOrder order = outboundService.doOutboundBatch(items, customer, externalNo, orderRemark, operator);
        return ApiResponse.ok(order);
    }

    /** 出库单详情(弹窗展示) */
    @GetMapping("/orders/{id}")
    public ApiResponse<OutboundOrder> getOrder(@PathVariable int id) {
        OutboundOrder o = outboundService.getOrder(id);
        if (o == null) throw new IllegalArgumentException("出库单不存在, id=" + id);
        return ApiResponse.ok(o);
    }

    /** 出库单列表(分页 + 多维筛选 + status 过滤) */
    @GetMapping("/orders")
    public ApiResponse<Map<String, Object>> listOrders(
            @RequestParam(required = false) String beginDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false, defaultValue = "DRAFT") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int ps = (pageSize <= 0 || pageSize > 200) ? 20 : pageSize;
        int p  = page <= 0 ? 1 : page;
        var list = outboundService.listOrders(beginDate, endDate, keyword, operator, status, p, ps);
        long total = outboundService.countOrders(beginDate, endDate, keyword, operator, status);
        return ApiResponse.ok(Map.of(
                "list", list,
                "total", total,
                "page", p,
                "pageSize", ps
        ));
    }

    /** 新建空草稿单(供 PDA 选/建) */
    @PostMapping("/orders")
    public ApiResponse<OutboundOrder> createDraft(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String operator = currentOperator(req);
        String customer   = asString(body.get("customer"));
        String externalNo = asString(body.get("externalNo"));
        String remark     = asString(body.get("remark"));
        return ApiResponse.ok(outboundService.createDraft(customer, externalNo, remark, operator));
    }

    /** PDA 扫一个码,验证并加入草稿单 */
    @PostMapping("/orders/{id}/scan")
    public ApiResponse<OutboundOrder> scanCode(@PathVariable int id, @RequestBody Map<String, Object> body) {
        String code = asString(body.get("code"));
        if (code == null || code.isBlank()) throw new IllegalArgumentException("请提供 24 位码");
        return ApiResponse.ok(outboundService.scanCodeToDraft(id, code));
    }

    /** 提交草稿单(DRAFT → COMMITTED) */
    @PostMapping("/orders/{id}/commit")
    public ApiResponse<OutboundOrder> commit(@PathVariable int id) {
        return ApiResponse.ok(outboundService.commitDraft(id));
    }

    /** 取消草稿单(DRAFT → CANCELLED) */
    @PostMapping("/orders/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable int id) {
        outboundService.cancelDraft(id);
        return ApiResponse.ok(null);
    }

    /** 出库单导出 CSV(明细) */
    @GetMapping(value = "/orders/{id}/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv(@PathVariable int id) {
        OutboundOrder o = outboundService.getOrder(id);
        if (o == null) throw new IllegalArgumentException("出库单不存在, id=" + id);

        StringBuilder sb = new StringBuilder();
        sb.append('\ufeff');
        sb.append("出库单号,").append(csv(o.getOrderNo())).append('\n');
        sb.append("领用方,").append(csv(o.getCustomer())).append('\n');
        sb.append("外部单号,").append(csv(o.getExternalNo())).append('\n');
        sb.append("操作员,").append(csv(o.getOperator())).append('\n');
        sb.append("整单备注,").append(csv(o.getRemark())).append('\n');
        sb.append("创建时间,").append(csv(o.getCreatedAt() == null ? "" : o.getCreatedAt().toString())).append('\n');
        sb.append("总数量,").append(o.getTotalQuantity() == null ? 0 : o.getTotalQuantity()).append('\n');
        sb.append("明细数,").append(o.getItemCount() == null ? 0 : o.getItemCount()).append('\n');
        sb.append('\n');
        sb.append("序号,SKU,商品名称,库位,数量,单价,行备注\n");
        int idx = 1;
        if (o.getItems() != null) {
            for (OutboundOrderItem it : o.getItems()) {
                sb.append(idx++).append(',')
                  .append(csv(it.getSku())).append(',')
                  .append(csv(it.getProductName())).append(',')
                  .append(csv(it.getLocation())).append(',')
                  .append(it.getQuantity() == null ? 0 : it.getQuantity()).append(',')
                  .append(it.getUnitPrice() == null ? "0.00" : it.getUnitPrice().toPlainString()).append(',')
                  .append(csv(it.getRowRemark())).append('\n');
            }
        }

        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        String fname = URLEncoder.encode(
                (o.getOrderNo() == null ? ("outbound_" + id) : o.getOrderNo()) + ".csv",
                StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.setContentDispositionFormData("attachment", fname);
        return new ResponseEntity<>(body, headers, 200);
    }

    private static String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String currentOperator(HttpServletRequest req) {
        String op = (String) req.getAttribute("username");
        return (op == null || op.isBlank()) ? "操作员" : op;
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString().trim()); }
        catch (Exception e) { return null; }
    }
    private static String asString(Object o) {
        return o == null ? null : o.toString().trim();
    }
}
