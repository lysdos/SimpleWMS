package com.wms.controller;

import com.wms.annotation.RequireRole;
import com.wms.model.ApiResponse;
import com.wms.model.Warehouse;
import com.wms.service.WarehouseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/warehouses")
public class WarehouseController {

    private final WarehouseService warehouseService;

    @Autowired
    public WarehouseController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    @GetMapping
    public ApiResponse<List<Warehouse>> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(warehouseService.list(keyword, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<Warehouse> get(@PathVariable int id) {
        Warehouse w = warehouseService.get(id);
        if (w == null) throw new NotFoundException("仓库不存在, id=" + id);
        return ApiResponse.ok(w);
    }

    @PostMapping
    @RequireRole("ADMIN")
    public ApiResponse<Warehouse> create(@RequestBody Warehouse w) {
        return ApiResponse.ok(warehouseService.create(w));
    }

    /**
     * 局部更新。收 Map 而不是 Warehouse:需要区分「字段没带」和「字段显式为 null」,
     * 前者保留原值(否则不带 enabled 会把停用的仓库静默重启)。
     */
    @PutMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Warehouse> update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(warehouseService.update(id, body));
    }

    @DeleteMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Void> delete(@PathVariable int id) {
        warehouseService.delete(id);
        return ApiResponse.ok();
    }
}
