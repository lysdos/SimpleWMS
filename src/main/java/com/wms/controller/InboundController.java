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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
        String createdBy  = currentUsername(req);

        if (productId == null) throw new IllegalArgumentException("请选择商品");
        if (quantity == null)  throw new IllegalArgumentException("请输入入库数量");
        if (quantity <= 0)     throw new IllegalArgumentException("入库数量必须大于 0");

        return ApiResponse.ok(inboundService.doInbound(productId, quantity, remark, createdBy));
    }

    /**
     * 新接口:批量入库
     * 入参:{ externalNo, remark, items:[{productId,quantity,supplier,rowRemark}] }
     * 返回:完整入库单(含明细)
     * 注意:supplier 移到明细行(同一单据可多供应商)
     */
    @PostMapping("/batch")
    public ApiResponse<InboundOrder> inboundBatch(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String createdBy = currentUsername(req);
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
            it.productionDate = asDate(r.get("productionDate"));
            it.shelfLifeDays  = asInt(r.get("shelfLifeDays"));
            items.add(it);
        }
        InboundOrder order = inboundService.doInboundBatch(items, externalNo, orderRemark, createdBy, null);
        return ApiResponse.ok(order);
    }

    /** 入库单详情(弹窗展示)— COMMITTED 单子需要返回 24 位码,用 getOrderWithCodes */
    @GetMapping("/orders/{id}")
    public ApiResponse<InboundOrder> getOrder(@PathVariable int id) {
        InboundOrder o = inboundService.getOrder(id);
        if (o == null) throw new NotFoundException("入库单不存在, id=" + id);
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
            @RequestParam(required = false) String createdBy,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int ps = (pageSize <= 0 || pageSize > 200) ? 20 : pageSize;
        int p  = page <= 0 ? 1 : page;
        var list = inboundService.listOrders(beginDate, endDate, keyword, createdBy, status, p, ps);
        long total = inboundService.countOrders(beginDate, endDate, keyword, createdBy, status);
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
        String createdBy = currentUsername(req);
        String externalNo = asString(body == null ? null : body.get("externalNo"));
        String remark = asString(body == null ? null : body.get("remark"));
        InboundOrder o = inboundService.createDraft(externalNo, remark, createdBy);
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
        it.productionDate = asDate(body.get("productionDate"));
        it.shelfLifeDays  = asInt(body.get("shelfLifeDays"));
        return ApiResponse.ok(inboundService.addItemToDraft(id, it));
    }

    /**
     * 修改 DRAFT 单头(外部单号/备注)。仅 DRAFT 可改。
     * body: { "externalNo": "可选", "remark": "可选" }
     */
    @PutMapping("/orders/{id}")
    public ApiResponse<InboundOrder> updateHeader(@PathVariable int id,
                                                  @RequestBody Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("请求体不能为空");
        return ApiResponse.ok(inboundService.updateDraftHeader(id,
                asString(body.get("externalNo")), asString(body.get("remark"))));
    }

    /**
     * 修改 DRAFT 单明细行(数量/供应商/行备注;productId 变更时换商品并重建占位码)。
     * body: { "quantity": 5, "productId": 3(可选,换商品时传), "supplier": "可选", "rowRemark": "可选" }
     */
    @PutMapping("/orders/{id}/items/{itemId}")
    public ApiResponse<InboundOrder> updateItem(@PathVariable int id, @PathVariable int itemId,
                                                @RequestBody Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("请求体不能为空");
        InboundService.BatchItem it = new InboundService.BatchItem();
        it.productId = asInt(body.get("productId"));
        it.quantity = asInt(body.get("quantity"));
        it.supplier = asString(body.get("supplier"));
        it.rowRemark = asString(body.get("rowRemark"));
        it.productionDate = asDate(body.get("productionDate"));
        it.shelfLifeDays  = asInt(body.get("shelfLifeDays"));
        return ApiResponse.ok(inboundService.updateDraftItem(id, itemId, it));
    }

    /**
     * 删除 DRAFT 单明细行(连带清理其 PENDING 占位库存单元)。
     */
    @DeleteMapping("/orders/{id}/items/{itemId}")
    public ApiResponse<InboundOrder> deleteItem(@PathVariable int id, @PathVariable int itemId) {
        return ApiResponse.ok(inboundService.deleteDraftItem(id, itemId));
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
        if (o == null) throw new NotFoundException("入库单不存在, id=" + id);

        StringBuilder sb = new StringBuilder();
        sb.append('\ufeff');
        sb.append("入库单号,").append(csv(o.getOrderNo())).append('\n');
        sb.append("外部单号,").append(csv(o.getExternalNo())).append('\n');
        sb.append("创建人,").append(csv(o.getCreatedBy())).append('\n');
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

    /** 批量导出入库单为 ZIP(每个订单一个 CSV) */
    @PostMapping("/orders/export.zip")
    public ResponseEntity<byte[]> exportZip(@RequestBody List<Integer> ids) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("请选择要导出的订单");
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                for (int id : ids) {
                    InboundOrder o = inboundService.getOrder(id);
                    if (o == null) continue;
                    String csvContent = buildCsv(o);
                    String entryName = (o.getOrderNo() == null ? ("inbound_" + id) : o.getOrderNo()) + ".csv";
                    zos.putNextEntry(new ZipEntry(entryName));
                    zos.write(csvContent.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
            byte[] zipBytes = baos.toByteArray();
            String fname = URLEncoder.encode("inbound_orders.zip", StandardCharsets.UTF_8).replace("+", "%20");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/zip"));
            headers.setContentDispositionFormData("attachment", fname);
            return new ResponseEntity<>(zipBytes, headers, 200);
        } catch (Exception e) {
            throw new RuntimeException("导出失败: " + e.getMessage(), e);
        }
    }

    private String buildCsv(InboundOrder o) {
        StringBuilder sb = new StringBuilder();
        sb.append('﻿');
        sb.append("入库单号,").append(csv(o.getOrderNo())).append('\n');
        sb.append("外部单号,").append(csv(o.getExternalNo())).append('\n');
        sb.append("创建人,").append(csv(o.getCreatedBy())).append('\n');
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
        return sb.toString();
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

    private static String currentUsername(HttpServletRequest req) {
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
    /** yyyy-MM-dd 字符串 → LocalDate;空/非法返回 null(效期字段均可选) */
    private static java.time.LocalDate asDate(Object o) {
        String s = asString(o);
        if (s == null || s.isEmpty()) return null;
        try { return java.time.LocalDate.parse(s); }
        catch (Exception e) { return null; }
    }
}
