package com.wms.service;

import com.wms.dao.LocationDao;
import com.wms.dao.WarehouseDao;
import com.wms.model.Location;
import com.wms.model.Warehouse;
import com.wms.util.CsvParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 库位 CSV 导入:解析 → 预检 →(可选)落库。
 *
 * 落库调 {@link LocationService#create},编码唯一、所属仓库必须存在且启用
 * 这些规则都在那一层。
 *
 * 「所属仓库」列**编码和名称都认**:库位管理页的筛选下拉显示的是仓库**名称**,
 * 而导出/接口里用的是 **id**。用户照着界面填多半写名称,照着接口文档填会写编码 ——
 * 两种都接受,比逼用户记编码友好。
 */
@Service
public class LocationImportService {

    private final LocationService locationService;
    private final LocationDao locationDao;
    private final WarehouseDao warehouseDao;

    private static final Map<String, List<String>> ALIASES = Map.of(
            "code",      List.of("编码", "库位编码", "库位"),
            "name",      List.of("名称", "库位名称"),
            "warehouse", List.of("所属仓库", "仓库", "仓库编码", "仓库名称"),
            "remark",    List.of("备注"),
            "enabled",   List.of("状态", "是否启用", "启用")
    );

    private static final List<String> REQUIRED = List.of("code");

    @Autowired
    public LocationImportService(LocationService locationService, LocationDao locationDao,
                                 WarehouseDao warehouseDao) {
        this.locationService = locationService;
        this.locationDao = locationDao;
        this.warehouseDao = warehouseDao;
    }

    @Transactional
    public ImportReport importLocations(byte[] fileBytes, boolean dryRun) {
        return run(fileBytes, dryRun);
    }

    ImportReport run(byte[] fileBytes, boolean dryRun) {
        CsvParser.Parsed parsed = CsvParser.decode(fileBytes);
        List<CsvParser.Row> rows = parsed.rows();

        ImportReport report = new ImportReport();
        report.setEncoding(parsed.encoding());

        ImportSupport.Header header = ImportSupport.findHeader(rows, ALIASES, REQUIRED);
        if (header == null) {
            report.error(1, "找不到表头行:前 10 行内需包含「编码」列。请用「下载模板」拿到正确格式。");
            report.setDryRun(dryRun);
            report.setOk(false);
            return report;
        }

        Set<String> existing = new HashSet<>();
        for (Location l : locationDao.findAll(null, null, null)) {
            if (l.getCode() != null) existing.add(ImportSupport.norm(l.getCode()));
        }
        // 仓库:编码与名称各建一份索引,让用户的写法两种都认
        Map<String, Warehouse> whByCode = new HashMap<>();
        Map<String, Warehouse> whByName = new HashMap<>();
        for (Warehouse w : warehouseDao.findAll(null, null)) {
            if (w.getCode() != null) whByCode.put(ImportSupport.norm(w.getCode()), w);
            if (w.getName() != null) whByName.putIfAbsent(ImportSupport.norm(w.getName()), w);
        }
        Set<String> inFile = new HashSet<>();

        List<Location> toCreate = new ArrayList<>();
        int fileRows = 0;

        for (int i = header.rowIndex() + 1; i < rows.size(); i++) {
            CsvParser.Row r = rows.get(i);
            List<String> cells = r.cells();
            if (ImportSupport.isBlankRow(cells)) continue;
            fileRows++;
            int lineNo = r.lineNo();
            Map<String, Integer> col = header.columns();

            String code = ImportSupport.cell(cells, col.get("code"));
            if (code.isBlank()) { report.error(lineNo, "库位编码不能为空"); continue; }
            if (code.length() > 32) { report.error(lineNo, "库位编码过长(最多 32): " + code); continue; }

            String normCode = ImportSupport.norm(code);
            if (existing.contains(normCode)) { report.error(lineNo, "库位编码已存在: " + code); continue; }
            if (!inFile.add(normCode)) { report.error(lineNo, "库位编码在文件中重复: " + code); continue; }

            String name = ImportSupport.cell(cells, col.get("name"));
            if (name.length() > 64) { report.error(lineNo, "库位名称过长(最多 64)"); continue; }

            String remark = ImportSupport.cell(cells, col.get("remark"));
            if (remark.length() > 255) { report.error(lineNo, "备注过长(最多 255)"); continue; }

            // 所属仓库:空 = 未分配;填了就必须存在且启用(与 LocationService.validate 同口径)
            Integer warehouseId = null;
            String whRaw = ImportSupport.cell(cells, col.get("warehouse"));
            if (!whRaw.isBlank()) {
                String k = ImportSupport.norm(whRaw);
                Warehouse w = whByCode.get(k);
                if (w == null) w = whByName.get(k);
                if (w == null) {
                    report.error(lineNo, "所属仓库不存在: " + whRaw + "(填仓库编码或名称)");
                    continue;
                }
                if (Boolean.FALSE.equals(w.getEnabled())) {
                    report.error(lineNo, "所属仓库已停用,不能再往里挂库位: " + w.getName());
                    continue;
                }
                warehouseId = w.getId();
            }

            String statusRaw = ImportSupport.cell(cells, col.get("enabled"));
            Boolean enabled = statusRaw.isBlank() ? Boolean.TRUE : ImportSupport.parseEnabled(statusRaw);
            if (enabled == null) {
                report.error(lineNo, "状态无法识别:「" + statusRaw + "」,请填 启用/停用(或留空)");
                continue;
            }

            Location l = new Location();
            l.setCode(code);
            l.setName(name.isBlank() ? code : name);   // 与 LocationService.validate 一致:空名退回编码
            l.setWarehouseId(warehouseId);
            l.setRemark(remark.isBlank() ? null : remark);
            l.setEnabled(enabled);
            toCreate.add(l);
        }

        if (fileRows == 0) report.error(1, "文件里没有数据行(只有表头)");

        report.setFileRows(fileRows);
        report.setDryRun(dryRun);
        report.setOk(!report.hasErrors());

        if (report.isOk() && !dryRun) {
            for (Location l : toCreate) locationService.create(l);
            report.setCreated(toCreate.size());
        }
        return report;
    }

    public static class ImportReport extends ImportSupport.Report {
        private int created;
        public int getCreated() { return created; }
        public void setCreated(int v) { this.created = v; }
    }
}
