package com.wms.controller;

import com.wms.annotation.RequireRole;
import com.wms.model.ApiResponse;
import com.wms.model.Product;
import com.wms.model.ProductSupplier;
import com.wms.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    @SuppressWarnings("unchecked")
    public ApiResponse<Product> create(@RequestBody Map<String, Object> body) {
        Product p = parseProduct(body);
        return ApiResponse.ok(productService.create(p));
    }

    @PutMapping("/{id}")
    @RequireRole("ADMIN")
    @SuppressWarnings("unchecked")
    public ApiResponse<Product> update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        Product p = parseProduct(body);
        p.setId(id);
        return ApiResponse.ok(productService.update(p));
    }

    @DeleteMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Void> delete(@PathVariable int id) {
        productService.delete(id);
        return ApiResponse.ok();
    }

    /** 从请求 Map 解析出 Product + 关联的供应商(可空) */
    private static Product parseProduct(Map<String, Object> body) {
        Product p = new Product();
        p.setSku((String) body.get("sku"));
        p.setName((String) body.get("name"));
        p.setCategory((String) body.get("category"));
        p.setUnit((String) body.get("unit"));
        p.setLocation((String) body.get("location"));
        p.setRemark((String) body.get("remark"));
        p.setLowStock(asInt(body.get("lowStock")));
        p.setUnitPrice(asBigDecimal(body.get("unitPrice")));
        p.setQuantity(asInt(body.get("quantity")));
        if (p.getQuantity() == null) p.setQuantity(0);
        if (p.getUnitPrice() == null) p.setUnitPrice(BigDecimal.ZERO);
        if (p.getLowStock() == null) p.setLowStock(10);

        Object suppliers = body.get("suppliers");
        if (suppliers instanceof List<?> rawList) {
            List<ProductSupplier> out = new ArrayList<>();
            for (Object o : rawList) {
                if (!(o instanceof Map<?, ?> m)) continue;
                ProductSupplier ps = new ProductSupplier();
                Object sid = m.get("supplierId");
                ps.setSupplierId(sid == null ? null : asInt(sid));
                ps.setIsDefault(Boolean.TRUE.equals(m.get("isDefault")));
                ps.setUnitPrice(asBigDecimal(m.get("unitPrice")));
                ps.setRemark((String) m.get("remark"));
                if (ps.getSupplierId() != null) out.add(ps);
            }
            // 默认值由 Service 层保证唯一
            p.setSuppliersAsList(out);
        }
        return p;
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString().trim()); } catch (Exception e) { return null; }
    }
    private static BigDecimal asBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(o.toString().trim()); } catch (Exception e) { return null; }
    }
}