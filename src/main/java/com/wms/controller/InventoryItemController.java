package com.wms.controller;

import com.wms.model.ApiResponse;
import com.wms.model.InventoryItem;
import com.wms.model.Product;
import com.wms.service.InventoryItemService;
import com.wms.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory/items")
public class InventoryItemController {

    private final InventoryItemService itemService;
    private final ProductService productService;

    @Autowired
    public InventoryItemController(InventoryItemService itemService, ProductService productService) {
        this.itemService = itemService;
        this.productService = productService;
    }

    /** 通过 24 位编码查库存单元(PDA 扫码查询用) */
    @GetMapping("/by-code/{code}")
    public ApiResponse<InventoryItem> byCode(@PathVariable String code) {
        InventoryItem it = itemService.findByCode(code);
        if (it == null) return ApiResponse.fail(404, "编码不存在: " + code);
        return ApiResponse.ok(it);
    }

    /** EPC = 24 位 code,接口独立便于未来扩展(写入 RFID) */
    @GetMapping("/by-epc/{epc}")
    public ApiResponse<InventoryItem> byEpc(@PathVariable String epc) {
        InventoryItem it = itemService.findByCode(epc);
        if (it == null) return ApiResponse.fail(404, "EPC 不存在: " + epc);
        return ApiResponse.ok(it);
    }

    /**
     * 通过 24 位编码找对应商品(PDA 入库扫码用)。
     * 流程:code → inventory_item → productId → Product。
     */
    @GetMapping("/by-code/{code}/product")
    public ApiResponse<Product> productByCode(@PathVariable String code) {
        InventoryItem it = itemService.findByCode(code);
        if (it == null) return ApiResponse.fail(404, "编码不存在: " + code);
        return productService.get(it.getProductId())
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.fail(404, "商品不存在: id=" + it.getProductId()));
    }
}