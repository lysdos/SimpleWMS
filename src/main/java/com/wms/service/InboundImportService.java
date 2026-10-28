package com.wms.service;

import com.wms.dao.LocationDao;
import com.wms.dao.ProductDao;
import com.wms.dao.SupplierDao;
import com.wms.model.InboundOrder;
import com.wms.model.Location;
import com.wms.model.Product;
import com.wms.model.Supplier;
import com.wms.util.CsvParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 入库单 CSV 导入:解析 → 预检 →(可选)落库。
 *
 * **为什么分"预检"和"落库"两趟**:导入一万件会生成一万个 24 位码和一万行占位单元,
 * 一旦中途有一行是错的,要么整单回滚(用户不知道是哪行错),要么半截成功(更难收拾)。
 * 所以先用 dryRun 把每一行的错误连同行号一次性报给用户,看完再提交。
 *
 * **为什么预检不自己实现业务校验**:真正的写入走 {@link InboundService#doInboundBatch},
 * 那条路里的校验(库位二次校验、行合并、逐件生成码)与写入是交织的,抽不出来。
 * 预检只是**建议性**的,提交才是权威的 —— 两者结果若有差异,只可能来自"预检到提交
 * 之间基础数据被改"(比如库位刚被停用),此时提交会如实报错。
 * 唯一必须严格一致的是**合并计数**,所以共用 {@link InboundService#mergeKeyOf}。
 */
@Service
public class InboundImportService {

    /** 单个文件最多多少件(不是多少行:一行可以填数量 500)。可配,因为后续到货会上万。 */
    private final int maxUnits;

    /** 预检最多报多少条错误 —— 一千行全错时不该让用户翻一千条 */
    private static final int MAX_ERRORS = 50;

    private static final DateTimeFormatter FMT_DASH = DateTimeFormatter.ofPattern("yyyy-M-d");
    private static final DateTimeFormatter FMT_SLASH = DateTimeFormatter.ofPattern("yyyy/M/d");
    private static final DateTimeFormatter FMT_DOT = DateTimeFormatter.ofPattern("yyyy.M.d");
    private static final DateTimeFormatter FMT_COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final InboundService inboundService;
    private final ProductDao productDao;
    private final LocationDao locationDao;
    private final SupplierDao supplierDao;

    @Autowired
    public InboundImportService(InboundService inboundService,
                                ProductDao productDao,
                                LocationDao locationDao,
                                SupplierDao supplierDao,
                                @Value("${wms.inbound.import.max-units:10000}") int maxUnits) {
        this.inboundService = inboundService;
        this.productDao = productDao;
        this.locationDao = locationDao;
        this.supplierDao = supplierDao;
        this.maxUnits = maxUnits;
    }

    // ==================== 对外入口 ====================

    public ImportReport importInbound(byte[] fileBytes, String externalNo, String remark,
                                      String createdBy, boolean dryRun) {
        ImportReport report = analyze(fileBytes);
        report.setDryRun(dryRun);
        // 有错就不落库。dryRun=true 也到此为止(这就是"预检零写入"的实现方式:
        // 下面这段是唯一会写库的地方,它只在没有错误且非 dryRun 时才执行)。
        if (!report.getErrors().isEmpty() || dryRun) {
            report.setOk(report.getErrors().isEmpty());
            return report;
        }
        InboundOrder order = inboundService.doInboundBatch(report.getItems(), externalNo, remark, createdBy);
        report.setOk(true);
        report.setOrderId(order.getId());
        report.setOrderNo(order.getOrderNo());
        return report;
    }

    /** 模板:带 BOM + 中文表头。和既有 4 个导出的表头语言一致(都只用中文,不随界面语言变)。 */
    public static byte[] template() {
        StringBuilder sb = new StringBuilder();
        sb.append('﻿');   // BOM:Excel 双击打开才不会把中文读成乱码
        sb.append("SKU,数量,供应商,生产日期,保质期天数,库位,行备注\n");
        sb.append("示例-请删掉本行,,,,,,\n");
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ==================== 解析 + 校验 ====================

    /** 解析并校验,产出报告(不做任何写入) */
    ImportReport analyze(byte[] fileBytes) {
        CsvParser.Parsed parsed = CsvParser.decode(fileBytes);
        List<CsvParser.Row> rows = parsed.rows();

        ImportReport report = new ImportReport();
        report.setEncoding(parsed.encoding());

        // 表头在**前 10 行**内找:用户常常在上面加一行"XX公司到货清单"。
        int headerIdx = -1;
        Map<String, Integer> col = null;
        for (int i = 0; i < Math.min(rows.size(), 10); i++) {
            Map<String, Integer> m = matchHeader(rows.get(i).cells());
            if (m != null) { headerIdx = i; col = m; break; }
        }
        if (col == null) {
            report.error(1, "找不到表头行:前 10 行内需包含「SKU」和「数量」两列。请用「下载模板」拿到正确格式。");
            return report;
        }
        int dataStart = headerIdx + 1;

        // 一次性把档案装进内存,避免逐行查库(N 行 = N 次查询,万行时不可接受)。
        // ⚠️ SKU 与库位码的排序规则都是 utf8mb4_unicode_ci(大小写不敏感),
        //    而 Java 的 Map 是敏感的 —— 必须统一归一化,否则会出现
        //    "商品明明存在,导入却说 SKU 不存在"这种极难排查的现象。
        Map<String, Product> productBySku = new HashMap<>();
        Map<Integer, Product> productById = new HashMap<>();
        for (Product p : productDao.findAll(null, null)) {
            if (p.getSku() != null) productBySku.put(norm(p.getSku()), p);
            if (p.getId() != null) productById.put(p.getId(), p);
        }
        Map<String, Location> locationByCode = new HashMap<>();
        for (Location l : locationDao.findAll(null, null, null)) {
            if (l.getCode() != null) locationByCode.put(norm(l.getCode()), l);
        }

        List<InboundService.BatchItem> items = new ArrayList<>();
        // 合并口径与落库完全一致(共用 mergeKeyOf),但这里只累计数量,不碰数据库
        Map<String, InboundService.BatchItem> merged = new LinkedHashMap<>();
        Map<String, Integer> mergedFirstLine = new LinkedHashMap<>();
        Map<String, Set<Integer>> mergedLines = new LinkedHashMap<>();

        int totalUnits = 0;
        int fileRows = 0;
        for (int i = dataStart; i < rows.size(); i++) {
            CsvParser.Row r = rows.get(i);
            int lineNo = r.lineNo();
            List<String> cells = r.cells();
            if (CsvParser.isBlankRow(cells)) continue;   // 空行不算错误,也不计入行数
            fileRows++;

            String skuRaw  = cell(cells, col.get("sku"));
            String qtyRaw  = cell(cells, col.get("quantity"));
            String supRaw  = cell(cells, col.get("supplier"));
            String dateRaw = cell(cells, col.get("productionDate"));
            String shelfRaw= cell(cells, col.get("shelfLifeDays"));
            String locRaw  = cell(cells, col.get("location"));
            String remarkRaw = cell(cells, col.get("rowRemark"));

            // --- SKU ---
            if (skuRaw.isBlank()) { report.error(lineNo, "SKU 不能为空"); continue; }
            Product p = productBySku.get(norm(skuRaw));
            if (p == null) {
                report.error(lineNo, "SKU 不存在: " + skuRaw + "(请先在「基础管理 → 商品管理」建档)");
                continue;
            }
            // --- 数量 ---
            Integer qty = parseInt(qtyRaw);
            if (qty == null || qty <= 0) {
                report.error(lineNo, "数量必须是大于 0 的整数: " + qtyRaw);
                continue;
            }
            // --- 生产日期(可选) ---
            LocalDate prodDate = null;
            if (!dateRaw.isBlank()) {
                prodDate = parseDate(dateRaw);
                if (prodDate == null) {
                    report.error(lineNo, "生产日期格式不对: " + dateRaw + "(支持 2026-09-01 / 2026/9/1 / 20260901)");
                    continue;
                }
            }
            // --- 保质期天数(可选) ---
            Integer shelf = null;
            if (!shelfRaw.isBlank()) {
                shelf = parseInt(shelfRaw);
                if (shelf == null || shelf <= 0) {
                    report.error(lineNo, "保质期天数必须是大于 0 的整数: " + shelfRaw);
                    continue;
                }
            }
            // --- 库位(可选;填了必须存在且启用 —— 与 addItemsToDraftInternal 同口径) ---
            String loc = null;
            if (!locRaw.isBlank()) {
                Location L = locationByCode.get(norm(locRaw));
                if (L == null) {
                    report.error(lineNo, "库位不存在: " + locRaw);
                    continue;
                }
                if (Boolean.FALSE.equals(L.getEnabled())) {
                    report.error(lineNo, "库位已停用: " + locRaw);
                    continue;
                }
                loc = L.getCode();   // 用档案里的规范写法(大小写可能与用户输入不同)
            }

            InboundService.BatchItem b = new InboundService.BatchItem();
            b.productId = p.getId();
            b.quantity = qty;
            b.supplier = supRaw.isBlank() ? null : supRaw.trim();
            b.productionDate = prodDate;
            b.shelfLifeDays = shelf;
            b.location = loc;
            b.rowRemark = remarkRaw.isBlank() ? null : remarkRaw.trim();

            String key = InboundService.mergeKeyOf(b);
            InboundService.BatchItem acc = merged.get(key);
            if (acc == null) {
                merged.put(key, b);
                mergedFirstLine.put(key, lineNo);
                Set<Integer> ls = new LinkedHashSet<>();
                ls.add(lineNo);
                mergedLines.put(key, ls);
            } else {
                acc.quantity += qty;
                mergedLines.get(key).add(lineNo);
            }
            totalUnits += qty;
        }

        if (fileRows == 0) {
            report.error(1, "文件里没有数据行(只有表头)");
        }
        if (totalUnits > maxUnits) {
            report.error(1, "本次共 " + totalUnits + " 件,超过单次上限 " + maxUnits
                    + " 件。请拆成多个文件分批导入(上限可在配置 wms.inbound.import.max-units 中调整)。");
        }

        // 预览行:让用户直观看到"文件 12 行 → 合并成 9 条明细",否则会以为导入丢数据
        List<PreviewLine> preview = new ArrayList<>();
        for (Map.Entry<String, InboundService.BatchItem> e : merged.entrySet()) {
            InboundService.BatchItem b = e.getValue();
            Product p = productById.get(b.productId);
            PreviewLine pl = new PreviewLine();
            pl.setSku(p == null ? null : p.getSku());
            pl.setProductName(p == null ? null : p.getName());
            pl.setQuantity(b.quantity);
            pl.setSupplier(b.supplier);
            pl.setProductionDate(b.productionDate == null ? null : b.productionDate.toString());
            pl.setShelfLifeDays(b.shelfLifeDays);
            pl.setLocation(b.location);
            pl.setRowRemark(b.rowRemark);
            pl.setSourceLines(new ArrayList<>(mergedLines.get(e.getKey())));
            preview.add(pl);
        }

        checkSuppliers(merged, mergedFirstLine, report);

        report.setFileRows(fileRows);
        report.setMergedLines(merged.size());
        report.setTotalUnits(totalUnits);
        report.setLines(preview);
        report.setItems(new ArrayList<>(merged.values()));
        report.setOk(report.getErrors().isEmpty());
        return report;
    }

    /**
     * 供应商检查:界面上供应商是下拉框(只列档案内项),CSV 是唯一能造出"档案外供应商"
     * 的入口。这不是洁癖 —— {@code InboundOrderDao.findOrders} 的 keyword 搜索靠
     * {@code EXISTS (... ii.supplier LIKE ?)},名字错一个字这批货以后就永远搜不出来。
     *
     * 只警告不阻断:现场经常是到货时才第一次录供应商,拦下来会逼着人先去建档。
     */
    private void checkSuppliers(Map<String, InboundService.BatchItem> merged,
                                Map<String, Integer> mergedFirstLine,
                                ImportReport report) {
        List<Supplier> all = supplierDao.findAll(null, null);
        Map<String, String> byNorm = new HashMap<>();     // 归一化 → 档案里的写法
        for (Supplier s : all) {
            if (s.getName() != null) byNorm.putIfAbsent(norm(s.getName()), s.getName());
        }
        if (byNorm.isEmpty()) return;

        // 不在档案里的,按名字聚合成一条汇总,而不是每行一条(逐行报会变成"每单必现",被无视)
        Map<String, Integer> unknown = new LinkedHashMap<>();
        for (Map.Entry<String, InboundService.BatchItem> e : merged.entrySet()) {
            String name = e.getValue().supplier;
            if (name == null) continue;
            String key = norm(name);
            if (byNorm.containsKey(key)) continue;

            // 近似匹配:手输的差异通常就是多个空格/全角半角/错一个字
            String similar = null;
            for (Map.Entry<String, String> cand : byNorm.entrySet()) {
                if (Math.abs(cand.getKey().length() - key.length()) <= 2
                        && editDistanceAtMost2(key, cand.getKey())) {
                    similar = cand.getValue();
                    break;
                }
            }
            if (similar != null) {
                report.warn("第 " + mergedFirstLine.get(e.getKey()) + " 行「" + name
                        + "」在供应商档案里是「" + similar + "」,确认是不是同一个(若不是,这批货以后按供应商搜不到)");
            } else {
                unknown.merge(name, 1, Integer::sum);
            }
        }
        if (!unknown.isEmpty()) {
            StringBuilder sb = new StringBuilder("以下 ").append(unknown.size())
                    .append(" 个供应商不在档案中:");
            unknown.forEach((n, c) -> sb.append(n).append("(").append(c).append(" 条)、"));
            sb.setLength(sb.length() - 1);
            sb.append("。不影响导入,但以后按供应商名搜索时,名字不一致会搜不到这批货。");
            report.warn(sb.toString());
        }
    }

    // ==================== 小工具 ====================

    /** 归一化:去全部空白 + 全角转半角 + 大写。用于大小写/空格/全角的宽松比对。 */
    static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '　') continue;   // 全角空格
            if (c >= '！' && c <= '～') c = (char) (c - 0xFEE0); // 全角 ASCII
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString().trim();
    }

    /** 编辑距离 ≤ 2 的快速判定(早退,不建完整矩阵) */
    private static boolean editDistanceAtMost2(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 2) return false;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > 2) return false;    // 这一行已经不可能回到 ≤2
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()] <= 2;
    }

    private static String cell(List<String> cells, Integer idx) {
        if (idx == null || idx < 0 || idx >= cells.size()) return "";
        String v = cells.get(idx);
        return v == null ? "" : v.trim();
    }

    /**
     * 表头匹配。按**列名**而非位置,所以用户可任意调列序、多余列直接忽略。
     * 返回 null 表示这行不是表头(继续往后找);返回 map 表示找到了。
     */
    private static Map<String, Integer> matchHeader(List<String> cells) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String h = norm(cells.get(i));
            if (h.isEmpty()) continue;
            switch (h) {
                case "SKU", "商品编码", "商品SKU" -> m.putIfAbsent("sku", i);
                case "数量", "入库数量", "件数" -> m.putIfAbsent("quantity", i);
                case "供应商" -> m.putIfAbsent("supplier", i);
                case "生产日期" -> m.putIfAbsent("productionDate", i);
                case "保质期天数", "保质期(天)", "保质期" -> m.putIfAbsent("shelfLifeDays", i);
                case "库位", "库位编码", "默认库位" -> m.putIfAbsent("location", i);
                case "行备注", "备注" -> m.putIfAbsent("rowRemark", i);
                default -> { /* 无关列,忽略 */ }
            }
        }
        // 只有 SKU + 数量 是最低要求(其余都可空);两个都在才认作表头行
        if (!m.containsKey("sku") || !m.containsKey("quantity")) return null;
        return m;
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            // 容忍 Excel 导出的 "5.0"
            String t = s.trim();
            if (t.endsWith(".0")) t = t.substring(0, t.length() - 2);
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 容忍 2026-09-01 / 2026/9/1 / 2026.9.1 / 20260901 / Excel 日期序列号 */
    static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        for (DateTimeFormatter f : new DateTimeFormatter[]{FMT_DASH, FMT_SLASH, FMT_DOT, FMT_COMPACT}) {
            try { return LocalDate.parse(t, f); } catch (DateTimeParseException ignored) { }
        }
        // Excel 把日期存成 1900 起算的序列号;单元格格式没设成日期时会导出成纯数字。
        // 只接受 20000~80000(1954-2039 左右),避免把数量之类的数字误会成日期。
        try {
            long serial = Long.parseLong(t);
            if (serial >= 20000 && serial <= 80000) {
                return LocalDate.of(1899, 12, 30).plusDays(serial);
            }
        } catch (NumberFormatException ignored) { }
        return null;
    }

    // ==================== 报告模型 ====================

    public static class ImportReport {
        private boolean ok;
        private boolean dryRun;
        private String encoding;
        private int fileRows;
        private int mergedLines;
        private int totalUnits;
        private Integer orderId;
        private String orderNo;
        private final List<RowError> errors = new ArrayList<>();
        /** 错误总数(可能大于 errors.size() —— 明细只保留前 MAX_ERRORS 条)。
         *  不单独计数的话,用户以为"就 50 个错",改完这 50 行再导还是会失败,来回好几轮。 */
        private int totalErrors;
        private final List<String> warnings = new ArrayList<>();
        private List<PreviewLine> lines = new ArrayList<>();
        /** 待写入的明细。**不能让 Jackson 序列化出去**:它是内部载体(带 _beforeQty/_afterQty),
         *  前端要的信息已经在 lines 里了。用 @JsonIgnore,而不是靠 transient —— record/POJO 的
         *  transient 只影响 Java 序列化,对 Jackson 无效。 */
        @com.fasterxml.jackson.annotation.JsonIgnore
        private List<InboundService.BatchItem> items = new ArrayList<>();

        void error(int line, String msg) {
            totalErrors++;
            if (errors.size() < MAX_ERRORS) errors.add(new RowError(line, msg));
        }
        void warn(String msg) { warnings.add(msg); }

        public boolean isOk() { return ok; }
        public void setOk(boolean v) { this.ok = v; }
        public boolean isDryRun() { return dryRun; }
        public void setDryRun(boolean v) { this.dryRun = v; }
        public String getEncoding() { return encoding; }
        public void setEncoding(String v) { this.encoding = v; }
        public int getFileRows() { return fileRows; }
        public void setFileRows(int v) { this.fileRows = v; }
        public int getMergedLines() { return mergedLines; }
        public void setMergedLines(int v) { this.mergedLines = v; }
        public int getTotalUnits() { return totalUnits; }
        public void setTotalUnits(int v) { this.totalUnits = v; }
        public Integer getOrderId() { return orderId; }
        public void setOrderId(Integer v) { this.orderId = v; }
        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String v) { this.orderNo = v; }
        public List<RowError> getErrors() { return errors; }
        public int getTotalErrors() { return totalErrors; }
        public void setTotalErrors(int v) { totalErrors = v; }
        public List<String> getWarnings() { return warnings; }
        public List<PreviewLine> getLines() { return lines; }
        public void setLines(List<PreviewLine> v) { this.lines = v; }
        public List<InboundService.BatchItem> getItems() { return items; }
        public void setItems(List<InboundService.BatchItem> v) { this.items = v; }
    }

    public static class RowError {
        private final int lineNo;
        private final String message;
        public RowError(int lineNo, String message) { this.lineNo = lineNo; this.message = message; }
        public int getLineNo() { return lineNo; }
        public String getMessage() { return message; }
    }

    public static class PreviewLine {
        private String sku, productName, supplier, productionDate, location, rowRemark;
        private Integer quantity, shelfLifeDays;
        private List<Integer> sourceLines;
        public String getSku() { return sku; } public void setSku(String v) { sku = v; }
        public String getProductName() { return productName; } public void setProductName(String v) { productName = v; }
        public String getSupplier() { return supplier; } public void setSupplier(String v) { supplier = v; }
        public String getProductionDate() { return productionDate; } public void setProductionDate(String v) { productionDate = v; }
        public String getLocation() { return location; } public void setLocation(String v) { location = v; }
        public String getRowRemark() { return rowRemark; } public void setRowRemark(String v) { rowRemark = v; }
        public Integer getQuantity() { return quantity; } public void setQuantity(Integer v) { quantity = v; }
        public Integer getShelfLifeDays() { return shelfLifeDays; } public void setShelfLifeDays(Integer v) { shelfLifeDays = v; }
        public List<Integer> getSourceLines() { return sourceLines; } public void setSourceLines(List<Integer> v) { sourceLines = v; }
    }
}
