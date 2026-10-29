package com.wms.controller;

import com.wms.annotation.RequireRole;
import com.wms.model.ApiResponse;
import com.wms.model.Supplier;
import com.wms.service.SupplierImportService;
import com.wms.service.SupplierService;
import com.wms.util.CsvWriter;
import com.wms.util.Dates;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private final SupplierService supplierService;
    private final SupplierImportService supplierImportService;

    @Autowired
    public SupplierController(SupplierService supplierService,
                              SupplierImportService supplierImportService) {
        this.supplierService = supplierService;
        this.supplierImportService = supplierImportService;
    }

    @GetMapping
    public ApiResponse<List<Supplier>> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(supplierService.list(keyword, status));
    }

    /**
     * 分页列表 —— 只给 Web 管理页用。全量的 {@link #list} 保持不变:
     * PDA 按 {@code List<Dto.Supplier>} 解析它,改形状会让供应商下拉解析失败。
     */
    @GetMapping("/page")
    public ApiResponse<com.wms.model.PageResult<Supplier>> listPage(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ApiResponse.ok(supplierService.listPage(keyword, status, page, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<Supplier> get(@PathVariable int id) {
        Supplier s = supplierService.get(id);
        if (s == null) throw new NotFoundException("供应商不存在");
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

    // ==================== 导出 / 导入 ====================

    private static final String[] EXPORT_HEADERS = {
            "ID", "名称", "联系人", "电话", "邮箱", "地址", "状态", "备注", "创建时间"
    };

    /** 导出(按筛选)。筛选参数与列表接口一致,不传即全量。 */
    @GetMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status) {
        return CsvWriter.download("suppliers.csv", EXPORT_HEADERS,
                toRows(supplierService.list(keyword, status)));
    }

    /** 导出选中项(POST + id 数组) */
    @PostMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportSelected(@RequestBody List<Integer> ids) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("请选择要导出的供应商");
        return CsvWriter.download("suppliers.csv", EXPORT_HEADERS, toRows(supplierService.listByIds(ids)));
    }

    private static List<Object[]> toRows(List<Supplier> list) {
        List<Object[]> rows = new ArrayList<>();
        for (Supplier s : list) {
            rows.add(new Object[]{
                    s.getId(), s.getName(), s.getContact(), s.getPhone(), s.getEmail(),
                    s.getAddress(), Boolean.FALSE.equals(s.getEnabled()) ? "停用" : "启用",
                    s.getRemark(), Dates.fmt(s.getCreatedAt())
            });
        }
        return rows;
    }

    /** 导入模板:带 BOM + 一行示例 */
    @GetMapping(value = "/import/template", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> importTemplate() {
        List<Object[]> sample = new ArrayList<>();
        sample.add(new Object[]{
                "示例供应商", "张三", "13800138000", "demo@example.com",
                "上海市某某路 1 号", "启用", "示例备注"
        });
        return CsvWriter.download("供应商导入模板.csv",
                new String[]{"名称", "联系人", "电话", "邮箱", "地址", "状态", "备注"}, sample);
    }

    /** CSV 批量导入。与其它模块同一交互:dryRun=true 只预检不落库。 */
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @RequireRole("ADMIN")
    public ApiResponse<SupplierImportService.ImportReport> importCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择要导入的 CSV 文件");
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("读取上传文件失败: " + e.getMessage());
        }
        return ApiResponse.ok(supplierImportService.importSuppliers(bytes, dryRun));
    }
}
