package com.wms.controller;

import com.wms.annotation.RequireRole;
import com.wms.model.ApiResponse;
import com.wms.model.Location;
import com.wms.service.LocationImportService;
import com.wms.service.LocationService;
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
@RequestMapping("/api/locations")
public class LocationController {

    private final LocationService locationService;
    private final LocationImportService locationImportService;

    @Autowired
    public LocationController(LocationService locationService,
                              LocationImportService locationImportService) {
        this.locationService = locationService;
        this.locationImportService = locationImportService;
    }

    @GetMapping
    public ApiResponse<List<Location>> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "warehouseId", required = false) Integer warehouseId) {
        return ApiResponse.ok(locationService.list(keyword, status, warehouseId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Location> get(@PathVariable int id) {
        Location l = locationService.get(id);
        if (l == null) throw new NotFoundException("库位不存在");
        return ApiResponse.ok(l);
    }

    @PostMapping
    @RequireRole("ADMIN")
    public ApiResponse<Location> create(@RequestBody Location l) {
        return ApiResponse.ok(locationService.create(l));
    }

    /**
     * 局部更新。收 Map 而不是 Location:需要区分「字段没带」和「字段显式为 null」,
     * 前者要保留原值(否则不带 enabled 会把停用项改回启用、不带 warehouseId 会把库位踢出仓库)。
     */
    @PutMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Location> update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(locationService.update(id, body));
    }

    @DeleteMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Void> delete(@PathVariable int id) {
        locationService.delete(id);
        return ApiResponse.ok();
    }

    // ==================== 导出 / 导入 ====================

    private static final String[] EXPORT_HEADERS = {"ID", "编码", "名称", "所属仓库", "状态", "备注", "创建时间"};

    /** 导出(按筛选)。筛选参数与列表接口一致,不传即全量。 */
    @GetMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "warehouseId", required = false) Integer warehouseId) {
        return CsvWriter.download("locations.csv", EXPORT_HEADERS,
                toRows(locationService.list(keyword, status, warehouseId)));
    }

    /** 导出选中项(POST + id 数组) */
    @PostMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportSelected(@RequestBody List<Integer> ids) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("请选择要导出的库位");
        return CsvWriter.download("locations.csv", EXPORT_HEADERS, toRows(locationService.listByIds(ids)));
    }

    private static List<Object[]> toRows(List<Location> list) {
        List<Object[]> rows = new ArrayList<>();
        for (Location l : list) {
            // 导出写仓库**名称**(与界面上看到的一致);导入时编码与名称都认,
            // 所以导出的文件可以直接改一改再导回来。未分配仓库写空,导入时同样表示"未分配"。
            rows.add(new Object[]{
                    l.getId(), l.getCode(), l.getName(), l.getWarehouseName(),
                    Boolean.FALSE.equals(l.getEnabled()) ? "停用" : "启用",
                    l.getRemark(), Dates.fmt(l.getCreatedAt())
            });
        }
        return rows;
    }

    /** 导入模板:带 BOM + 一行示例 */
    @GetMapping(value = "/import/template", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> importTemplate() {
        List<Object[]> sample = new ArrayList<>();
        sample.add(new Object[]{"A-01-01", "A区01排01位", "WH-DEFAULT", "启用", "示例备注"});
        return CsvWriter.download("库位导入模板.csv",
                new String[]{"编码", "名称", "所属仓库", "状态", "备注"}, sample);
    }

    /** CSV 批量导入。dryRun=true 只预检不落库。 */
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @RequireRole("ADMIN")
    public ApiResponse<LocationImportService.ImportReport> importCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择要导入的 CSV 文件");
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("读取上传文件失败: " + e.getMessage());
        }
        return ApiResponse.ok(locationImportService.importLocations(bytes, dryRun));
    }
}
