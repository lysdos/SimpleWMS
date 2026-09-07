package com.wms.controller;

import com.wms.model.ApiResponse;
import com.wms.model.InventoryItem;
import com.wms.service.InventoryItemService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory/items")
public class InventoryItemController {

    private final InventoryItemService itemService;

    @Autowired
    public InventoryItemController(InventoryItemService itemService) {
        this.itemService = itemService;
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
}
