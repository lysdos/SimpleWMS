package com.wms.controller;

import com.wms.annotation.RequireRole;
import com.wms.model.ApiResponse;
import com.wms.model.Product;
import com.wms.model.ProductSupplier;
import com.wms.service.ProductImportService;
import com.wms.service.ProductService;
import com.wms.util.CsvWriter;
import com.wms.util.Dates;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final ProductImportService productImportService;

    @Autowired
    public ProductController(ProductService productService,
                             ProductImportService productImportService) {
        this.productService = productService;
        this.productImportService = productImportService;
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

    // ==================== 导出 / 导入 ====================

    private static final String[] EXPORT_HEADERS = {
            "ID", "SKU", "名称", "分类", "单位", "默认库位", "数量", "单价",
            "低库存预警", "默认保质期天数", "临期预警天数", "默认供应商", "全部供应商", "备注", "创建时间"
    };

    /** 导出(按筛选)。筛选参数与列表接口完全一致,不传即全量。 */
    @GetMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "supplier", required = false) String supplier) {
        return CsvWriter.download("products.csv", EXPORT_HEADERS,
                toRows(productService.list(keyword, supplier)));
    }

    /**
     * 导出选中项。
     *
     * 用 POST + body 传 id 列表,而不是把 id 拼进 query:ID 多了会超 URL 长度限制,
     * 也不用操心编码。仓库里出库单的批量导出(export.zip)早就是这个做法。
     */
    @PostMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportSelected(@RequestBody List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请选择要导出的商品");
        }
        return CsvWriter.download("products.csv", EXPORT_HEADERS, toRows(productService.listByIds(ids)));
    }

    private static List<Object[]> toRows(List<Product> list) {
        List<Object[]> rows = new ArrayList<>();
        for (Product p : list) {
            // 供应商有两列:默认供应商单列(便于筛选),全部供应商分号分隔(信息完整)
            String defaultSup = null;
            StringBuilder all = new StringBuilder();
            if (p.getSuppliers() != null) {
                for (Product.SupplierRef s : p.getSuppliers()) {
                    if (all.length() > 0) all.append(';');
                    all.append(s.getName());
                    if (Boolean.TRUE.equals(s.getIsDefault())) defaultSup = s.getName();
                }
            }
            rows.add(new Object[]{
                    p.getId(), p.getSku(), p.getName(), p.getCategory(), p.getUnit(),
                    p.getLocation(), p.getQuantity(), p.getUnitPrice(), p.getLowStock(),
                    p.getDefaultShelfLifeDays(), p.getExpiryWarnDays(),
                    defaultSup, all.length() == 0 ? null : all.toString(),
                    p.getRemark(), Dates.fmt(p.getCreatedAt())
            });
        }
        return rows;
    }

    /** 导入模板:带 BOM 的中文表头 + 一行示例(每格都给出该列"正确长什么样") */
    @GetMapping(value = "/import/template", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> importTemplate() {
        List<Object[]> sample = new ArrayList<>();
        sample.add(new Object[]{
                "IMP-DEMO-001", "示例商品", "默认", "件", "", "12.50",
                "10", "365", "30", "示例供应商", "示例供应商;另一个供应商", "示例备注"
        });
        return CsvWriter.download("商品导入模板.csv",
                new String[]{"SKU", "名称", "分类", "单位", "默认库位", "单价",
                        "低库存预警", "默认保质期天数", "临期预警天数", "默认供应商", "全部供应商", "备注"},
                sample);
    }

    /**
     * CSV 批量导入。与入库导入同一个交互:dryRun=true 只预检不落库。
     * 权限与其它写接口一致 —— 只有 ADMIN 能改商品资料。
     */
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @RequireRole("ADMIN")
    public ApiResponse<ProductImportService.ImportReport> importCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要导入的 CSV 文件");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("读取上传文件失败: " + e.getMessage());
        }
        return ApiResponse.ok(productImportService.importProducts(bytes, dryRun));
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