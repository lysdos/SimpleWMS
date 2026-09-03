package com.wms.controller;

import com.wms.annotation.RequireRole;
import com.wms.model.ApiResponse;
import com.wms.model.Product;
import com.wms.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    @Autowired
    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public ApiResponse<List<Product>> list(@RequestParam(value = "keyword", required = false) String keyword) {
        return ApiResponse.ok(productService.list(keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<Product> get(@PathVariable int id) {
        return productService.get(id)
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.fail(404, "商品不存在"));
    }

    @PostMapping
    @RequireRole("ADMIN")
    public ApiResponse<Product> create(@RequestBody Product p) {
        return ApiResponse.ok(productService.create(p));
    }

    @PutMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Product> update(@PathVariable int id, @RequestBody Product p) {
        p.setId(id);
        return ApiResponse.ok(productService.update(p));
    }

    @DeleteMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Void> delete(@PathVariable int id) {
        productService.delete(id);
        return ApiResponse.ok();
    }
}