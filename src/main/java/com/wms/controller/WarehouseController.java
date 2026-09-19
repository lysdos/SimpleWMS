package com.wms.controller;

import com.wms.annotation.RequireRole;
import com.wms.model.ApiResponse;
import com.wms.model.Warehouse;
import com.wms.service.WarehouseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @PutMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Warehouse> update(@PathVariable int id, @RequestBody Warehouse w) {
        w.setId(id);
        return ApiResponse.ok(warehouseService.update(w));
    }

    @DeleteMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Void> delete(@PathVariable int id) {
        warehouseService.delete(id);
        return ApiResponse.ok();
    }
}
