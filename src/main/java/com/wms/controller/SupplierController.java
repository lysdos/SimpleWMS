package com.wms.controller;

import com.wms.annotation.RequireRole;
import com.wms.model.ApiResponse;
import com.wms.model.Supplier;
import com.wms.service.SupplierService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private final SupplierService supplierService;

    @Autowired
    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    @GetMapping
    public ApiResponse<List<Supplier>> list(@RequestParam(value = "keyword", required = false) String keyword) {
        return ApiResponse.ok(supplierService.list(keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<Supplier> get(@PathVariable int id) {
        Supplier s = supplierService.get(id);
        if (s == null) return ApiResponse.fail(404, "供应商不存在");
        return ApiResponse.ok(s);
    }

    @PostMapping
    @RequireRole("ADMIN")
    public ApiResponse<Supplier> create(@RequestBody Supplier s) {
        return ApiResponse.ok(supplierService.create(s));
    }

    @PutMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Supplier> update(@PathVariable int id, @RequestBody Supplier s) {
        s.setId(id);
        return ApiResponse.ok(supplierService.update(s));
    }

    @DeleteMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Void> delete(@PathVariable int id) {
        supplierService.delete(id);
        return ApiResponse.ok();
    }
}
