package com.wms.service;

import com.wms.dao.LocationDao;
import com.wms.dao.ProductDao;
import com.wms.dao.SupplierDao;
import com.wms.model.Location;
import com.wms.model.Product;
import com.wms.model.ProductSupplier;
import com.wms.model.Supplier;
import com.wms.util.CsvParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商品 CSV 导入:解析 → 预检 →(可选)落库。
 *
 * 落库直接调既有的 {@link ProductService#create},不另开写入路径 ——
 * SKU 唯一、默认库位必须存在这些规则都在那一层,绕过去就等于第二套实现。
 *
 * **不接受「数量」**:`product.quantity` 由出入库/报废维护(`ProductDao.update`
 * 故意不写它)。文件里出现该列时给警告而不是报错 —— 用户可能只是把导出文件
 * 直接改一改拿来导入,不该因此被拦住;但也绝不能让它看起来"导入进去了"。
 */
@Service
public class ProductImportService {

    private final ProductService productService;
    private final ProductDao productDao;
    private final LocationDao locationDao;
    private final SupplierDao supplierDao;
    /** 单次导入行数上限,见 application.yml 的 wms.import.max-rows */
    private final int maxRows;

    /** 逻辑列 → 可接受的中文写法。第一项是模板里用的那个。 */
    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("sku",        List.of("SKU", "商品编码", "商品SKU")),
            Map.entry("name",       List.of("名称", "商品名称")),
            Map.entry("category",   List.of("分类", "类别")),
            Map.entry("unit",       List.of("单位", "计量单位")),
            Map.entry("location",   List.of("默认库位", "库位", "库位编码")),
            Map.entry("unitPrice",  List.of("单价")),
            Map.entry("lowStock",   List.of("低库存预警", "预警阈值", "低库存")),
            Map.entry("shelfLife",  List.of("默认保质期天数", "保质期天数", "保质期(天)")),
            Map.entry("warnDays",   List.of("临期预警天数", "预警天数")),
            Map.entry("remark",     List.of("备注")),
            Map.entry("defaultSupplier", List.of("默认供应商")),
            Map.entry("suppliers",  List.of("全部供应商", "供应商")),
            // 只用来识别"文件里有这一列",从而给出忽略警告
            Map.entry("quantity",   List.of("数量", "库存数量", "当前数量"))
    );

    private static final List<String> REQUIRED = List.of("sku", "name");

    @Autowired
    public ProductImportService(ProductService productService, ProductDao productDao,
                                LocationDao locationDao, SupplierDao supplierDao,
                                @org.springframework.beans.factory.annotation.Value("${wms.import.max-rows:5000}") int maxRows) {
        this.productService = productService;
        this.productDao = productDao;
        this.locationDao = locationDao;
        this.supplierDao = supplierDao;
        this.maxRows = maxRows;
    }

    // ==================== 入口 ====================

    /**
     * 导入入口。
     *
     * **必须整体一个事务**:预检通过不代表落库一定成功 —— 预检到这里之间可能有人
     * 抢先建了同名 SKU,或者库位刚被删。那种情况下如果每条各自提交,会留下"导了一半"
     * 的商品,而用户看到的是一句报错,不知道哪些进去了。
     * (预检是建议性的,提交才是权威的;这里靠事务保证"要么全进、要么全不进"。)
     */
    @org.springframework.transaction.annotation.Transactional
    public ImportReport importProducts(byte[] fileBytes, boolean dryRun) {
        return run(fileBytes, dryRun);
    }

    // ==================== 解析 + 校验 ====================

    ImportReport run(byte[] fileBytes, boolean dryRun) {
        CsvParser.Parsed parsed = CsvParser.decode(fileBytes);
        List<CsvParser.Row> rows = parsed.rows();

        ImportReport report = new ImportReport();
        report.setEncoding(parsed.encoding());
        report.preview("sku", "name", "supplierCount");

        ImportSupport.Header header = ImportSupport.findHeader(rows, ALIASES, REQUIRED);
        if (header == null) {
            report.error(1, "找不到表头行:前 10 行内需包含「SKU」和「名称」两列。请用「下载模板」拿到正确格式。");
            report.setDryRun(dryRun);
            report.setOk(false);
            return report;
        }

        // Excel 用户常常是把导出文件改一改拿来导入 —— 里面有「数量」列就明确告诉他会被忽略
        if (header.has("quantity")) {
            report.warn("已忽略「数量」列:库存数量只能通过入库/出库调整,不能由商品资料改。");
        }

        // 档案一次性装进内存,避免逐行查库。
        // SKU / 库位码的排序规则是 utf8mb4_unicode_ci(大小写不敏感),Java 的 Map 却敏感,
        // 所以统一用 norm() 做 key。
        Map<String, Product> bySku = new HashMap<>();
        for (Product p : productDao.findAll(null, null)) {
            if (p.getSku() != null) bySku.put(ImportSupport.norm(p.getSku()), p);
        }
        Map<String, Location> byLoc = new HashMap<>();
        for (Location l : locationDao.findAll(null, null, null)) {
            if (l.getCode() != null) byLoc.put(ImportSupport.norm(l.getCode()), l);
        }
        Map<String, Supplier> bySupplier = new HashMap<>();
        for (Supplier s : supplierDao.findAll(null, null)) {
            if (s.getName() != null) bySupplier.put(ImportSupport.norm(s.getName()), s);
        }

        // 文件内重复 SKU:同一文件里出现两次同一个 SKU,第一次能过、第二次必然撞唯一索引,
        // 与其让用户看到数据库报错,不如在这里就指出来。
        Set<String> seenSku = new HashSet<>();

        List<Product> toCreate = new ArrayList<>();
        int fileRows = 0;

        for (int i = header.rowIndex() + 1; i < rows.size(); i++) {
            CsvParser.Row r = rows.get(i);
            List<String> cells = r.cells();
            if (ImportSupport.isBlankRow(cells)) continue;
            fileRows++;
            // 超限立即停:再往下解析只是白白消耗时间,而结果一定是"导不了"
            if (ImportSupport.overRowLimit(fileRows, maxRows, report)) break;
            int lineNo = r.lineNo();
            Map<String, Integer> col = header.columns();

            String sku = ImportSupport.cell(cells, col.get("sku"));
            String name = ImportSupport.cell(cells, col.get("name"));

            if (sku.isBlank()) { report.error(lineNo, "SKU 不能为空"); continue; }
            if (name.isBlank()) { report.error(lineNo, "名称 不能为空"); continue; }

            String normSku = ImportSupport.norm(sku);
            if (bySku.containsKey(normSku)) {
                report.error(lineNo, "SKU 已存在: " + sku);
                continue;
            }
            if (!seenSku.add(normSku)) {
                report.error(lineNo, "SKU 在文件中重复: " + sku);
                continue;
            }

            // 默认库位:空 = 交给系统兜底;填了必须存在且启用(与 ProductService.validateLocation 同口径)
            String locRaw = ImportSupport.cell(cells, col.get("location"));
            String location = null;
            if (!locRaw.isBlank()) {
                Location L = byLoc.get(ImportSupport.norm(locRaw));
                if (L == null) { report.error(lineNo, "默认库位不存在: " + locRaw); continue; }
                if (Boolean.FALSE.equals(L.getEnabled())) { report.error(lineNo, "默认库位已停用: " + locRaw); continue; }
                location = L.getCode();
            }

            BigDecimal price = ImportSupport.parseDecimal(ImportSupport.cell(cells, col.get("unitPrice")));
            if (price != null && price.signum() < 0) { report.error(lineNo, "单价不能小于 0"); continue; }

            Integer lowStock = ImportSupport.parseInt(ImportSupport.cell(cells, col.get("lowStock")));
            if (lowStock != null && lowStock < 0) { report.error(lineNo, "低库存预警不能小于 0"); continue; }
            Integer shelf = ImportSupport.parseInt(ImportSupport.cell(cells, col.get("shelfLife")));
            Integer warnDays = ImportSupport.parseInt(ImportSupport.cell(cells, col.get("warnDays")));

            // 供应商关联:名称匹配档案,匹配不上只警告不阻断
            List<ProductSupplier> links = resolveSuppliers(
                    ImportSupport.cell(cells, col.get("defaultSupplier")),
                    ImportSupport.cell(cells, col.get("suppliers")),
                    bySupplier, report, lineNo);
            Integer supplierCount = links == null ? 0 : links.size();

            Product p = new Product();
            p.setSku(sku);
            p.setName(name);
            p.setCategory(blankToNull(ImportSupport.cell(cells, col.get("category"))));
            p.setUnit(blankToNull(ImportSupport.cell(cells, col.get("unit"))));
            p.setLocation(location);
            p.setUnitPrice(price == null ? BigDecimal.ZERO : price);
            p.setLowStock(lowStock == null ? 10 : lowStock);
            p.setDefaultShelfLifeDays(shelf);
            p.setExpiryWarnDays(warnDays);
            p.setRemark(blankToNull(ImportSupport.cell(cells, col.get("remark"))));
            p.setQuantity(0);   // 新建商品数量恒为 0,只能靠入库增加
            if (links != null) p.setSuppliersAsList(links);

            toCreate.add(p);
            report.previewLine(lineNo, sku, name, supplierCount);
        }

        if (fileRows == 0) report.error(1, "文件里没有数据行(只有表头)");

        report.setFileRows(fileRows);
        report.setDryRun(dryRun);
        report.setOk(!report.hasErrors());

        if (report.isOk() && !dryRun) {
            int created = 0;
            for (Product p : toCreate) {
                productService.create(p);
                created++;
            }
            report.setCreated(created);
        }
        return report;
    }

    /**
     * 把「默认供应商」与「全部供应商」解析成 {@link ProductSupplier} 列表。
     *
     * 两列都空 → 返回 null:调用方据此**不写**关联(`ProductService` 里 null 表示
     * "不动关联"),避免把商品原有的供应商清空。
     *
     * 名称匹配不上的只警告、跳过该关联 —— 与该模块"供应商不存在不阻断"的既有口径一致。
     */
    private List<ProductSupplier> resolveSuppliers(String defaultName, String allNames,
                                                   Map<String, Supplier> bySupplier,
                                                   ImportReport report, int lineNo) {
        // 先把两列合并成「名称 → 是否默认」
        Map<String, Boolean> wanted = new java.util.LinkedHashMap<>();
        if (allNames != null && !allNames.isBlank()) {
            for (String n : allNames.split("[;；、,，]")) {
                String t = n.trim();
                if (!t.isEmpty()) wanted.putIfAbsent(t, Boolean.FALSE);
            }
        }
        if (defaultName != null && !defaultName.isBlank()) {
            // 默认项即使没出现在「全部供应商」里也要并进来 —— 否则默认关系会丢
            wanted.put(defaultName.trim(), Boolean.TRUE);
        }
        if (wanted.isEmpty()) return null;

        List<ProductSupplier> out = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : wanted.entrySet()) {
            Supplier s = bySupplier.get(ImportSupport.norm(e.getKey()));
            if (s == null) {
                report.warn("第 " + lineNo + " 行供应商「" + e.getKey() + "」不在供应商档案中,已跳过该关联。");
                continue;
            }
            ProductSupplier ps = new ProductSupplier();
            ps.setSupplierId(s.getId());
            ps.setIsDefault(e.getValue());
            out.add(ps);
        }
        // 一个都没解析出来时返回 null,避免把商品原有供应商清空
        return out.isEmpty() ? null : out;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    // ==================== 报告 ====================

    /**
     * 商品导入的报告。预览行由**基类** {@link ImportSupport.Report} 提供 ——
     * 这里不能再定义 `lines` / `getLines()`,否则 Jackson 会因
     * "Conflicting getter definitions" 在启动时直接失败。
     */
    public static class ImportReport extends ImportSupport.Report {
        private int created;
        public int getCreated() { return created; }
        public void setCreated(int v) { this.created = v; }
    }
}
