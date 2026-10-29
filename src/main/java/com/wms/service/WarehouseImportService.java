package com.wms.service;

import com.wms.dao.WarehouseDao;
import com.wms.model.Warehouse;
import com.wms.util.CsvParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 仓库 CSV 导入:解析 → 预检 →(可选)落库。
 *
 * 落库调 {@link WarehouseService#create},编码唯一靠数据库的 `uk_warehouse_code` 保证。
 */
@Service
public class WarehouseImportService {

    private final WarehouseService warehouseService;
    private final WarehouseDao warehouseDao;
    /** 单次导入行数上限,见 application.yml 的 wms.import.max-rows */
    private final int maxRows;

    private static final Map<String, List<String>> ALIASES = Map.of(
            "code",    List.of("编码", "仓库编码", "仓库代码"),
            "name",    List.of("名称", "仓库名称"),
            "remark",  List.of("备注"),
            "enabled", List.of("状态", "是否启用", "启用")
    );

    private static final List<String> REQUIRED = List.of("code", "name");

    @Autowired
    public WarehouseImportService(WarehouseService warehouseService, WarehouseDao warehouseDao,
                                  @org.springframework.beans.factory.annotation.Value("${wms.import.max-rows:5000}") int maxRows) {
        this.warehouseService = warehouseService;
        this.warehouseDao = warehouseDao;
        this.maxRows = maxRows;
    }

    @Transactional
    public ImportReport importWarehouses(byte[] fileBytes, boolean dryRun) {
        return run(fileBytes, dryRun);
    }

    ImportReport run(byte[] fileBytes, boolean dryRun) {
        CsvParser.Parsed parsed = CsvParser.decode(fileBytes);
        List<CsvParser.Row> rows = parsed.rows();

        ImportReport report = new ImportReport();
        report.setEncoding(parsed.encoding());

        ImportSupport.Header header = ImportSupport.findHeader(rows, ALIASES, REQUIRED);
        if (header == null) {
            report.error(1, "找不到表头行:前 10 行内需包含「编码」和「名称」两列。请用「下载模板」拿到正确格式。");
            report.setDryRun(dryRun);
            report.setOk(false);
            return report;
        }

        Set<String> existing = new HashSet<>();
        for (Warehouse w : warehouseDao.findAll(null, null)) {
            if (w.getCode() != null) existing.add(ImportSupport.norm(w.getCode()));
        }
        Set<String> inFile = new HashSet<>();

        List<Warehouse> toCreate = new ArrayList<>();
        int fileRows = 0;

        for (int i = header.rowIndex() + 1; i < rows.size(); i++) {
            CsvParser.Row r = rows.get(i);
            List<String> cells = r.cells();
            if (ImportSupport.isBlankRow(cells)) continue;
            fileRows++;
            if (ImportSupport.overRowLimit(fileRows, maxRows, report)) break;
            int lineNo = r.lineNo();
            Map<String, Integer> col = header.columns();

            String code = ImportSupport.cell(cells, col.get("code"));
            String name = ImportSupport.cell(cells, col.get("name"));
            if (code.isBlank()) { report.error(lineNo, "仓库编码不能为空"); continue; }
            if (name.isBlank()) { report.error(lineNo, "仓库名称不能为空"); continue; }
            if (code.length() > 32) { report.error(lineNo, "仓库编码过长(最多 32): " + code); continue; }
            if (name.length() > 64) { report.error(lineNo, "仓库名称过长(最多 64)"); continue; }

            String normCode = ImportSupport.norm(code);
            if (existing.contains(normCode)) { report.error(lineNo, "仓库编码已存在: " + code); continue; }
            if (!inFile.add(normCode)) { report.error(lineNo, "仓库编码在文件中重复: " + code); continue; }

            String remark = ImportSupport.cell(cells, col.get("remark"));
            if (remark.length() > 255) { report.error(lineNo, "备注过长(最多 255)"); continue; }

            String statusRaw = ImportSupport.cell(cells, col.get("enabled"));
            Boolean enabled = statusRaw.isBlank() ? Boolean.TRUE : ImportSupport.parseEnabled(statusRaw);
            if (enabled == null) {
                report.error(lineNo, "状态无法识别:「" + statusRaw + "」,请填 启用/停用(或留空)");
                continue;
            }

            Warehouse w = new Warehouse();
            w.setCode(code);
            w.setName(name);
            w.setRemark(remark.isBlank() ? null : remark);
            w.setEnabled(enabled);
            toCreate.add(w);
        }

        if (fileRows == 0) report.error(1, "文件里没有数据行(只有表头)");

        report.setFileRows(fileRows);
        report.setDryRun(dryRun);
        report.setOk(!report.hasErrors());

        if (report.isOk() && !dryRun) {
            for (Warehouse w : toCreate) warehouseService.create(w);
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
