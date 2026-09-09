package com.wms.controller;

import com.wms.model.ApiResponse;
import com.wms.model.InboundOrder;
import com.wms.model.InboundOrderItem;
import com.wms.model.InventoryLog;
import com.wms.service.InboundService;
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
@RequestMapping("/api/inbound")
public class InboundController {

    private final InboundService inboundService;

    @Autowired
    public InboundController(InboundService inboundService) {
        this.inboundService = inboundService;
    }

    /** 老接口:单货入库(兼容,内部走 DRAFT 路径) */
    @PostMapping
    public ApiResponse<InboundOrder> inbound(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Integer productId = asInt(body.get("productId"));
        Integer quantity  = asInt(body.get("quantity"));
        String remark    = asString(body.get("remark"));
        String operator  = currentOperator(req);

        if (productId == null) throw new IllegalArgumentException("请选择商品");
        if (quantity == null)  throw new IllegalArgumentException("请输入入库数量");
        if (quantity <= 0)     throw new IllegalArgumentException("入库数量必须大于 0");

        return ApiResponse.ok(inboundService.doInbound(productId, quantity, remark, operator));
    }

    /**
     * 新接口:批量入库
     * 入参:{ externalNo, remark, items:[{productId,quantity,supplier,rowRemark}] }
     * 返回:完整入库单(含明细)
     * 注意:supplier 移到明细行(同一单据可多供应商)
     */
    @PostMapping("/batch")
    public ApiResponse<InboundOrder> inboundBatch(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String operator = currentOperator(req);
        String externalNo = asString(body.get("externalNo"));
        String orderRemark = asString(body.get("remark"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawItems = (List<Map<String, Object>>) body.get("items");
        if (rawItems == null || rawItems.isEmpty()) {
            throw new IllegalArgumentException("请至少添加一条入库明细");
        }
        List<InboundService.BatchItem> items = new ArrayList<>();
        for (Map<String, Object> r : rawItems) {
            InboundService.BatchItem it = new InboundService.BatchItem();
            it.productId  = asInt(r.get("productId"));
            it.quantity   = asInt(r.get("quantity"));
            it.supplier   = asString(r.get("supplier"));
            it.rowRemark  = asString(r.get("rowRemark"));
            items.add(it);
        }
        InboundOrder order = inboundService.doInboundBatch(items, externalNo, orderRemark, operator, null);
        return ApiResponse.ok(order);
    }

    /** 入库单详情(弹窗展示)— COMMITTED 单子需要返回 24 位码,用 getOrderWithCodes */
    @GetMapping("/orders/{id}")
    public ApiResponse<InboundOrder> getOrder(@PathVariable int id) {
        InboundOrder o = inboundService.getOrder(id);
        if (o == null) throw new IllegalArgumentException("入库单不存在, id=" + id);
        // COMMITTED 单子的 inbound_order_item.codes 需要从 inventory_item 查,补一次 codes
        if (InboundOrder.STATUS_COMMITTED.equals(o.getStatus())) {
            InboundOrder withCodes = inboundService.getOrderWithCodes(id);
            if (withCodes != null) return ApiResponse.ok(withCodes);
        }
        return ApiResponse.ok(o);
    }

    /** 入库单列表(分页 + 多维筛选) */
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
        var list = inboundService.listOrders(beginDate, endDate, keyword, operator, status, p, ps);
        long total = inboundService.countOrders(beginDate, endDate, keyword, operator, status);
        return ApiResponse.ok(Map.of(
                "list", list,
                "total", total,
                "page", p,
                "pageSize", ps
        ));
    }

    // ============================================================
    // PDA 入库登记(DRAFT / COMMITTED / CANCELLED)流程
    // ============================================================

    /**
     * 创建空草稿入库单。
     * body: { externalNo?, remark? }
     * 返回:完整 InboundOrder(status=DRAFT,total_quantity=0,item_count=0)
     */
    @PostMapping("/orders")
    public ApiResponse<InboundOrder> createDraft(@RequestBody Map<String, Object> body,
                                                  HttpServletRequest req) {
        String operator = currentOperator(req);
        String externalNo = asString(body == null ? null : body.get("externalNo"));
        String remark = asString(body == null ? null : body.get("remark"));
        InboundOrder o = inboundService.createDraft(externalNo, remark, operator);
        return ApiResponse.ok(o);
    }

    /**
     * 往草稿单追加一个明细行(关键:生成 N 个 inventory_item 24 位码)。
     * body: { productId, quantity, supplier?, rowRemark? }
     * 返回:更新后的 InboundOrder(含明细 + codes)
     */
    @PostMapping("/orders/{id}/items")
    public ApiResponse<InboundOrder> addItem(@PathVariable int id,
                                              @RequestBody Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("请求体不能为空");
        Integer productId = asInt(body.get("productId"));
        Integer quantity = asInt(body.get("quantity"));
        if (productId == null) throw new IllegalArgumentException("请选择商品");
        if (quantity == null || quantity <= 0) throw new IllegalArgumentException("入库数量必须大于 0");

        InboundService.BatchItem it = new InboundService.BatchItem();
        it.productId = productId;
        it.quantity = quantity;
        it.supplier = asString(body.get("supplier"));
        it.rowRemark = asString(body.get("rowRemark"));
        return ApiResponse.ok(inboundService.addItemToDraft(id, it));
    }

    /**
     * 提交草稿单(DRAFT → COMMITTED)。返回完整单 + 每行 inventory_item.code 列表。
     */
    @PostMapping("/orders/{id}/commit")
    public ApiResponse<InboundOrder> commit(@PathVariable int id) {
        return ApiResponse.ok(inboundService.commitDraft(id));
    }

    /**
     * 取消草稿单(DRAFT → CANCELLED)。
     * 回滚:删该单的 inventory_item + 商品 quantity 减回去 + 删 inbound_order_item + 删相关 inventory_log。
     */
    @PostMapping("/orders/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable int id) {
        inboundService.cancelDraft(id);
        return ApiResponse.ok();
    }

    /** 入库单导出 CSV(明细) */
    @GetMapping(value = "/orders/{id}/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv(@PathVariable int id) {
        InboundOrder o = inboundService.getOrder(id);
        if (o == null) throw new IllegalArgumentException("入库单不存在, id=" + id);

        StringBuilder sb = new StringBuilder();
        sb.append('\ufeff');
        sb.append("入库单号,").append(csv(o.getOrderNo())).append('\n');
        sb.append("外部单号,").append(csv(o.getExternalNo())).append('\n');
        sb.append("操作员,").append(csv(o.getOperator())).append('\n');
        sb.append("整单备注,").append(csv(o.getRemark())).append('\n');
        sb.append("创建时间,").append(csv(o.getCreatedAt() == null ? "" : o.getCreatedAt().toString())).append('\n');
        sb.append("总数量,").append(o.getTotalQuantity() == null ? 0 : o.getTotalQuantity()).append('\n');
        sb.append("明细数,").append(o.getItemCount() == null ? 0 : o.getItemCount()).append('\n');
        sb.append('\n');
        sb.append("序号,供应商,SKU,商品名称,库位,数量,单价,行备注\n");
        int idx = 1;
        if (o.getItems() != null) {
            for (InboundOrderItem it : o.getItems()) {
                sb.append(idx++).append(',')
                  .append(csv(it.getSupplier())).append(',')
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
                (o.getOrderNo() == null ? ("inbound_" + id) : o.getOrderNo()) + ".csv",
                StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.setContentDispositionFormData("attachment", fname);
        return new ResponseEntity<>(body, headers, 200);
    }

    private static String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        // 含逗号/引号/换行的,用双引号包起来,内部双引号转义
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
