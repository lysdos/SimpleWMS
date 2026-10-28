package com.wms.controller;

import com.wms.annotation.RequireRole;
import com.wms.model.ApiResponse;
import com.wms.model.Warehouse;
import com.wms.service.WarehouseImportService;
import com.wms.service.WarehouseService;
import com.wms.util.CsvWriter;
import com.wms.util.Dates;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/warehouses")
public class WarehouseController {

    private final WarehouseService warehouseService;
    private final WarehouseImportService warehouseImportService;

    @Autowired
    public WarehouseController(WarehouseService warehouseService,
                               WarehouseImportService warehouseImportService) {
        this.warehouseService = warehouseService;
        this.warehouseImportService = warehouseImportService;
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

    // ==================== 导出 / 导入 ====================

    private static final String[] EXPORT_HEADERS = {"ID", "编码", "名称", "库位数", "状态", "备注", "创建时间"};

    /** 导出(按筛选)。筛选参数与列表接口一致,不传即全量。 */
    @GetMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status) {
        return CsvWriter.download("warehouses.csv", EXPORT_HEADERS,
                toRows(warehouseService.list(keyword, status)));
    }

    /** 导出选中项(POST + id 数组) */
    @PostMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportSelected(@RequestBody List<Integer> ids) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("请选择要导出的仓库");
        return CsvWriter.download("warehouses.csv", EXPORT_HEADERS, toRows(warehouseService.listByIds(ids)));
    }

    private static List<Object[]> toRows(List<Warehouse> list) {
        List<Object[]> rows = new ArrayList<>();
        for (Warehouse w : list) {
            rows.add(new Object[]{
                    w.getId(), w.getCode(), w.getName(),
                    w.getLocationCount() == null ? 0 : w.getLocationCount(),
                    Boolean.FALSE.equals(w.getEnabled()) ? "停用" : "启用",
                    w.getRemark(), Dates.fmt(w.getCreatedAt())
            });
        }
        return rows;
    }

    /** 导入模板:带 BOM + 一行示例 */
    @GetMapping(value = "/import/template", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> importTemplate() {
        List<Object[]> sample = new ArrayList<>();
        sample.add(new Object[]{"WH-DEMO", "示例仓库", "启用", "示例备注"});
        return CsvWriter.download("仓库导入模板.csv",
                new String[]{"编码", "名称", "状态", "备注"}, sample);
    }

    /** CSV 批量导入。dryRun=true 只预检不落库。 */
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @RequireRole("ADMIN")
    public ApiResponse<WarehouseImportService.ImportReport> importCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择要导入的 CSV 文件");
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("读取上传文件失败: " + e.getMessage());
        }
        return ApiResponse.ok(warehouseImportService.importWarehouses(bytes, dryRun));
    }
}
