package com.wms.service;

import com.wms.dao.SupplierDao;
import com.wms.model.Supplier;
import com.wms.util.CsvParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 供应商 CSV 导入:解析 → 预检 →(可选)落库。
 *
 * 落库直接调 {@link SupplierService#create},不另开写入路径 —— 名称唯一靠
 * 数据库的 `uk_supplier_name` 保证(2026-10-28 新增),那条路上的
 * DuplicateKeyException 分支到这会才真正生效。
 */
@Service
public class SupplierImportService {

    private final SupplierService supplierService;
    private final SupplierDao supplierDao;

    private static final Map<String, List<String>> ALIASES = Map.of(
            "name",    List.of("名称", "供应商名称", "供应商"),
            "contact", List.of("联系人"),
            "phone",   List.of("电话", "联系电话", "手机"),
            "email",   List.of("邮箱", "电子邮件"),
            "address", List.of("地址"),
            "remark",  List.of("备注"),
            "enabled", List.of("状态", "是否启用", "启用")
    );

    private static final List<String> REQUIRED = List.of("name");

    @Autowired
    public SupplierImportService(SupplierService supplierService, SupplierDao supplierDao) {
        this.supplierService = supplierService;
        this.supplierDao = supplierDao;
    }

    @org.springframework.transaction.annotation.Transactional
    public ImportReport importSuppliers(byte[] fileBytes, boolean dryRun) {
        return run(fileBytes, dryRun);
    }

    ImportReport run(byte[] fileBytes, boolean dryRun) {
        CsvParser.Parsed parsed = CsvParser.decode(fileBytes);
        List<CsvParser.Row> rows = parsed.rows();

        ImportReport report = new ImportReport();
        report.setEncoding(parsed.encoding());

        ImportSupport.Header header = ImportSupport.findHeader(rows, ALIASES, REQUIRED);
        if (header == null) {
            report.error(1, "找不到表头行:前 10 行内需包含「名称」列。请用「下载模板」拿到正确格式。");
            report.setDryRun(dryRun);
            report.setOk(false);
            return report;
        }

        // 已有名称一次性装进内存比对。名称排序规则 utf8mb4_unicode_ci 大小写不敏感,
        // Java 的 Map 敏感,所以统一用 norm() 做 key。
        Set<String> existing = new HashSet<>();
        for (Supplier s : supplierDao.findAll(null, null)) {
            if (s.getName() != null) existing.add(ImportSupport.norm(s.getName()));
        }
        Set<String> inFile = new HashSet<>();

        List<Supplier> toCreate = new ArrayList<>();
        int fileRows = 0;

        for (int i = header.rowIndex() + 1; i < rows.size(); i++) {
            CsvParser.Row r = rows.get(i);
            List<String> cells = r.cells();
            if (ImportSupport.isBlankRow(cells)) continue;
            fileRows++;
            int lineNo = r.lineNo();
            Map<String, Integer> col = header.columns();

            String name = ImportSupport.cell(cells, col.get("name"));
            if (name.isBlank()) { report.error(lineNo, "供应商名称不能为空"); continue; }
            if (name.length() > 128) { report.error(lineNo, "供应商名称过长(最多 128): " + name); continue; }

            String normName = ImportSupport.norm(name);
            if (existing.contains(normName)) { report.error(lineNo, "供应商名称已存在: " + name); continue; }
            if (!inFile.add(normName)) { report.error(lineNo, "供应商名称在文件中重复: " + name); continue; }

            String statusRaw = ImportSupport.cell(cells, col.get("enabled"));
            Boolean enabled = statusRaw.isBlank() ? Boolean.TRUE : ImportSupport.parseEnabled(statusRaw);
            if (enabled == null) {
                report.error(lineNo, "状态无法识别:「" + statusRaw + "」,请填 启用/停用(或留空)");
                continue;
            }

            String email = blankToNull(ImportSupport.cell(cells, col.get("email")));
            if (email != null && !email.contains("@")) {
                report.error(lineNo, "邮箱格式不对: " + email);
                continue;
            }

            Supplier s = new Supplier();
            s.setName(name);
            s.setContact(blankToNull(ImportSupport.cell(cells, col.get("contact"))));
            s.setPhone(blankToNull(ImportSupport.cell(cells, col.get("phone"))));
            s.setEmail(email);
            s.setAddress(blankToNull(ImportSupport.cell(cells, col.get("address"))));
            s.setRemark(blankToNull(ImportSupport.cell(cells, col.get("remark"))));
            s.setEnabled(enabled);

            toCreate.add(s);
        }

        if (fileRows == 0) report.error(1, "文件里没有数据行(只有表头)");

        report.setFileRows(fileRows);
        report.setDryRun(dryRun);
        report.setOk(!report.hasErrors());

        if (report.isOk() && !dryRun) {
            for (Supplier s : toCreate) supplierService.create(s);
            report.setCreated(toCreate.size());
        }
        return report;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public static class ImportReport extends ImportSupport.Report {
        private int created;
        public int getCreated() { return created; }
        public void setCreated(int v) { this.created = v; }
    }
}
