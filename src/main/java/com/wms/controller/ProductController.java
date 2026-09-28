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
    public ApiResponse<List<Product>> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "supplier", required = false) String supplier) {
        return ApiResponse.ok(productService.list(keyword, supplier));
    }

    @GetMapping("/{id}")
    public ApiResponse<Product> get(@PathVariable int id) {
        Product p = productService.get(id)
                .orElseThrow(() -> new NotFoundException("商品不存在"));
        return ApiResponse.ok(p);
    }

    @PostMapping
    @RequireRole("ADMIN")
    @SuppressWarnings("unchecked")
    public ApiResponse<Product> create(@RequestBody Map<String, Object> body) {
        Product p = parseProduct(body, true);
        return ApiResponse.ok(productService.create(p));
    }

    @PutMapping("/{id}")
    @RequireRole("ADMIN")
    @SuppressWarnings("unchecked")
    public ApiResponse<Product> update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        Product p = parseProduct(body, false);
        p.setId(id);
        return ApiResponse.ok(productService.update(p));
    }

    @DeleteMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Void> delete(@PathVariable int id) {
        productService.delete(id);
        return ApiResponse.ok();
    }

    /**
     * 从请求 Map 解析出 Product + 关联的供应商(可空)。
     *
     * `creating=false`(更新)时**不读 quantity**:数量不是商品资料的一部分,
     * 只能由出入库/报废改动。原因见 {@link com.wms.dao.ProductDao#update(Product)} ——
     * 共用一个解析方法 + 缺省补 0,会让一个不带 quantity 的 PUT 把库存清零。
     * 新建时仍可填初始数量,所以只有创建路径读它。
     */
    private static Product parseProduct(Map<String, Object> body, boolean creating) {
        Product p = new Product();
        p.setSku((String) body.get("sku"));
        p.setName((String) body.get("name"));
        p.setCategory((String) body.get("category"));
        p.setUnit((String) body.get("unit"));
        p.setLocation((String) body.get("location"));
        p.setRemark((String) body.get("remark"));
        p.setLowStock(asInt(body.get("lowStock")));
        p.setDefaultShelfLifeDays(asInt(body.get("defaultShelfLifeDays")));
        p.setExpiryWarnDays(asInt(body.get("expiryWarnDays")));
        p.setUnitPrice(asBigDecimal(body.get("unitPrice")));
        if (creating) {
            p.setQuantity(asInt(body.get("quantity")));
            if (p.getQuantity() == null) p.setQuantity(0);
        }
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