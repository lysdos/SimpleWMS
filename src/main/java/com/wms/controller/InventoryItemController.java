package com.wms.controller;

import com.wms.annotation.RequireRole;
import com.wms.model.ApiResponse;
import com.wms.model.InventoryItem;
import com.wms.model.Product;
import com.wms.service.BarcodeService;
import com.wms.service.InventoryItemService;
import com.wms.service.ProductService;
import com.wms.util.Dates;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory/items")
public class InventoryItemController {

    private final InventoryItemService itemService;
    private final ProductService productService;
    private final BarcodeService barcodeService;

    @Autowired
    public InventoryItemController(InventoryItemService itemService,
                                   ProductService productService,
                                   BarcodeService barcodeService) {
        this.itemService = itemService;
        this.productService = productService;
        this.barcodeService = barcodeService;
    }

    // ====================== 列表 + 详情 ======================

    /** 列表(库存管理页)— 支持 sku / location / warehouseId / status / 效期过滤 + 分页 */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) Integer warehouseId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String expiryStatus,
            @RequestParam(required = false) String expiryFrom,
            @RequestParam(required = false) String expiryTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int ps = (pageSize <= 0 || pageSize > 200) ? 20 : pageSize;
        int p = page <= 0 ? 1 : page;
        var list = itemService.list(sku, location, status, expiryStatus, expiryFrom, expiryTo,
                warehouseId, p, ps);
        long total = itemService.count(sku, location, status, expiryStatus, expiryFrom, expiryTo,
                warehouseId);
        return ApiResponse.ok(Map.of(
                "list", list,
                "total", total,
                "page", p,
                "pageSize", ps
        ));
    }

    /** 详情 */
    @GetMapping("/{id}")
    public ApiResponse<InventoryItem> get(@PathVariable int id) {
        InventoryItem it = itemService.get(id);
        if (it == null) throw new NotFoundException("库存单元不存在");
        return ApiResponse.ok(it);
    }

    /** 列出某入库单产生的所有库存单元(用于入库单详情展示每件条码) */
    @GetMapping("/by-inbound/{orderId}")
    public ApiResponse<List<InventoryItem>> listByInbound(@PathVariable int orderId) {
        return ApiResponse.ok(itemService.listByInboundOrder(orderId));
    }

    /** 列出某出库单消耗的所有库存单元(用于出库单详情展示每件条码) */
    @GetMapping("/by-outbound/{orderId}")
    public ApiResponse<List<InventoryItem>> listByOutbound(@PathVariable int orderId) {
        return ApiResponse.ok(itemService.listByOutboundOrder(orderId));
    }

    // ====================== PDA 扫码查询(B1 新增) ======================

    /** 通过 24 位编码查库存单元(PDA 扫码查询用) */
    @GetMapping("/by-code/{code}")
    public ApiResponse<InventoryItem> byCode(@PathVariable String code) {
        InventoryItem it = itemService.findByCode(code);
        if (it == null) throw new NotFoundException("编码不存在: " + code);
        return ApiResponse.ok(it);
    }

    /** EPC = 24 位 code,接口独立便于未来扩展(写入 RFID) */
    @GetMapping("/by-epc/{epc}")
    public ApiResponse<InventoryItem> byEpc(@PathVariable String epc) {
        InventoryItem it = itemService.findByCode(epc);
        if (it == null) throw new NotFoundException("EPC 不存在: " + epc);
        return ApiResponse.ok(it);
    }

    /**
     * 通过 24 位编码找对应商品(PDA 入库扫码用)。
     * 流程:code → inventory_item → productId → Product。
     */
    @GetMapping("/by-code/{code}/product")
    public ApiResponse<Product> productByCode(@PathVariable String code) {
        InventoryItem it = itemService.findByCode(code);
        if (it == null) throw new NotFoundException("编码不存在: " + code);
        Product p = productService.get(it.getProductId())
                .orElseThrow(() -> new NotFoundException("商品不存在: id=" + it.getProductId()));
        return ApiResponse.ok(p);
    }

    // ====================== 条码 PNG ======================

    /** 条形码 PNG(CODE_128) */
    @GetMapping("/{id}/barcode.png")
    public ResponseEntity<byte[]> barcodePng(@PathVariable int id) throws Exception {
        InventoryItem it = itemService.get(id);
        if (it == null) return ResponseEntity.status(404).build();
        byte[] png = barcodeService.renderCode128Png(it.getCode());
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.IMAGE_PNG);
        h.setContentDispositionFormData("inline", it.getCode() + ".png");
        return new ResponseEntity<>(png, h, 200);
    }

    // ====================== 报废 / 调拨 ======================

    /**
     * 报废。
     *
     * 保持 ADMIN:SCRAPPED 是**终态且不可逆**(见 InventoryItemDao 的终态说明,没有任何接口
     * 能把状态改回去),而它是唯一一处"能静默减少可用库存"的日常操作 —— 误报废只能进 SQL 修。
     * 这正是它与调拨的差别:调拨可逆,所以不设门槛。
     */
    @PostMapping("/{id}/scrap")
    @RequireRole("ADMIN")
    public ApiResponse<Void> scrap(@PathVariable int id,
                                   @RequestBody(required = false) Map<String, String> body,
                                   HttpServletRequest req) {
        String remark = body == null ? null : body.get("remark");
        itemService.scrap(id, remark, currentUsername(req));
        return ApiResponse.ok();
    }

    /**
     * 调拨(改库位,状态保持 IN_STOCK)。
     *
     * 不设角色门槛:只挪库位、总量不变、留 TRANSFER 流水,挪错了再挪一次就是 ——
     * 这是现场常态作业(货放错架、主仓放不下进副仓),卡住操作员没道理。
     * 对比 {@link #scrap}:那个不可逆,保持 ADMIN。
     */
    @PostMapping("/{id}/transfer")
    public ApiResponse<Void> transfer(@PathVariable int id,
                                      @RequestBody Map<String, String> body,
                                      HttpServletRequest req) {
        String toLocation = body == null ? null : body.get("toLocation");
        itemService.transfer(id, toLocation, currentUsername(req));
        return ApiResponse.ok();
    }

    // ====================== CSV 导出 ======================

    @GetMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) Integer warehouseId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String expiryStatus,
            @RequestParam(required = false) String expiryFrom,
            @RequestParam(required = false) String expiryTo) {
        var list = itemService.list(sku, location, status, expiryStatus, expiryFrom, expiryTo,
                warehouseId, 1, 5000);

        StringBuilder sb = new StringBuilder();
        sb.append('﻿');
        sb.append("24位编码,SKU,商品名称,仓库,库位,原始数量,剩余数量,状态,创建人,创建时间," +
                "入库单ID,出库单ID,报废时间,报废原因,调拨时间,调拨来源,调拨目标,生产日期,到期日\n");
        for (InventoryItem it : list) {
            sb.append(csv(it.getCode())).append(',')
              .append(csv(it.getSku())).append(',')
              .append(csv(it.getProductName())).append(',')
              .append(csv(it.getWarehouseName())).append(',')
              .append(csv(it.getLocationCode())).append(',')
              .append(it.getQuantity() == null ? 0 : it.getQuantity()).append(',')
              .append(it.getRemaining() == null ? 0 : it.getRemaining()).append(',')
              .append(csv(statusLabel(it.getStatus()))).append(',')
              .append(csv(it.getCreatedBy())).append(',')
              .append(csv(Dates.fmt(it.getCreatedAt()))).append(',')
              .append(it.getInboundOrderId() == null ? "" : it.getInboundOrderId()).append(',')
              .append(it.getOutboundOrderId() == null ? "" : it.getOutboundOrderId()).append(',')
              .append(csv(Dates.fmt(it.getScrappedAt()))).append(',')
              .append(csv(it.getScrappedRemark())).append(',')
              .append(csv(Dates.fmt(it.getTransferredAt()))).append(',')
              .append(csv(it.getTransferredFrom())).append(',')
              .append(csv(it.getTransferredTo())).append(',')
              .append(csv(it.getProductionDate() == null ? "" : it.getProductionDate().toString())).append(',')
              .append(csv(it.getExpiryDate() == null ? "" : it.getExpiryDate().toString())).append('\n');
        }

        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        String filename = URLEncoder.encode("inventory-" +
                java.time.LocalDate.now() + ".csv", StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        h.setContentDispositionFormData("attachment", filename);
        return new ResponseEntity<>(body, h, 200);
    }

    private static String statusLabel(String s) {
        if (s == null) return "";
        switch (s) {
            case "IN_STOCK":    return "在库";
            case "OUT":         return "已出库";
            case "SCRAPPED":    return "已报废";
            case "TRANSFERRED": return "已调拨";
            // 这两个状态此前没映射,导出时会吐英文原值(库里 20 个入库草稿占位单元就是 PENDING)
            case "PENDING":     return "待入库";
            case "RESERVED":    return "已预留";
            default: return s;
        }
    }

    private static String currentUsername(HttpServletRequest req) {
        String op = (String) req.getAttribute("username");
        return (op == null || op.isBlank()) ? "操作员" : op;
    }

    private static String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
