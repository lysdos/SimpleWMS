package com.wms.service;

import com.wms.util.CsvParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基础管理各模块 CSV 导入的共用件:表头定位、报告模型、名称归一化。
 *
 * 从入库导入(`InboundImportService`)里提炼出来的 —— 那套逻辑已经过现场验证,
 * 5 个模块各抄一遍的话,改一处漏四处是必然的。
 *
 * 不含"落库":各模块的导入服务解析完直接调既有的 `XxxService.create`,
 * 不另开写入路径(唯一性校验、删除保护等规则都在那一层)。
 */
public final class ImportSupport {

    /** 预检最多报多少条错误 —— 一千行全错时不该让用户翻一千条 */
    public static final int MAX_ERRORS = 50;

    /** 预览最多保留多少条有效行。无限保留的话,导入一万条会往响应里塞约 670KB 的无用数据 */
    public static final int MAX_PREVIEW_LINES = 200;

    /**
     * 行数上限检查。返回 true 表示已超限、调用方应立即停止解析。
     *
     * **为什么要有上限**:基础数据导入没有逐件的天然上限(不像入库按件),
     * 而 nginx 只给 /api/inbound/import 放宽到 300 秒,基础数据走的是 60 秒。
     * 实测商品导入约 155 行/秒,一万行要 60 秒以上 —— 超时后用户只会看到
     * 一个没头没脑的网关错误,不如提前告诉他"文件太大,请分批"。
     */
    public static boolean overRowLimit(int fileRows, int maxRows, Report report) {
        if (fileRows <= maxRows) return false;
        report.error(0, "文件超过单次上限 " + maxRows + " 行,请拆分成多个文件分批导入"
                + "(上限可在配置 wms.import.max-rows 中调整)。");
        return true;
    }

    /** 表头在前多少行内找。用户常常在上面加一行"XX公司清单"。 */
    private static final int HEADER_SCAN_ROWS = 10;

    private ImportSupport() {}

    // ==================== 表头 ====================

    /** 列名归一化:去空白 + 全角转半角 + 大写。用于表头与名称的宽松匹配。 */
    public static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '　') continue;   // 全角空格
            if (c >= '！' && c <= '～') c = (char) (c - 0xFEE0);   // 全角 ASCII
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString().trim();
    }

    /**
     * 在前 10 行内定位表头,并按**列名**建立「逻辑列 → 列下标」映射。
     *
     * @param rows       解析后的行
     * @param aliases    逻辑列名 → 该列可接受的所有中文写法(会各自归一化)
     * @param required   这些逻辑列必须存在,缺一个就返回 null(继续找下一行)
     * @return 命中的表头,或 null
     */
    public static Header findHeader(List<CsvParser.Row> rows,
                                    Map<String, List<String>> aliases,
                                    List<String> required) {
        // 预处理:归一化后的写法 → 逻辑列
        Map<String, String> byAlias = new HashMap<>();
        aliases.forEach((logical, names) -> {
            for (String n : names) byAlias.putIfAbsent(norm(n), logical);
        });

        for (int i = 0; i < Math.min(rows.size(), HEADER_SCAN_ROWS); i++) {
            List<String> cells = rows.get(i).cells();
            Map<String, Integer> cols = new HashMap<>();
            for (int c = 0; c < cells.size(); c++) {
                String logical = byAlias.get(norm(cells.get(c)));
                if (logical != null) cols.putIfAbsent(logical, c);
            }
            if (cols.keySet().containsAll(required)) {
                return new Header(i, cols);
            }
        }
        return null;
    }

    /** 命中的表头:它所在的行下标 + 逻辑列到列下标的映射 */
    public record Header(int rowIndex, Map<String, Integer> columns) {
        public boolean has(String logical) { return columns.containsKey(logical); }
        public Integer indexOf(String logical) { return columns.get(logical); }
    }

    /** 取某逻辑列在这一行的值(越界/缺列返回空串) */
    public static String cell(List<String> cells, Integer idx) {
        if (idx == null || idx < 0 || idx >= cells.size()) return "";
        String v = cells.get(idx);
        return v == null ? "" : v.trim();
    }

    /** 整行都是空白(含只有逗号的空行) */
    public static boolean isBlankRow(List<String> cells) {
        return CsvParser.isBlankRow(cells);
    }

    // ==================== 报告 ====================

    public static class RowError {
        private final int lineNo;
        private final String message;
        public RowError(int lineNo, String message) { this.lineNo = lineNo; this.message = message; }
        public int getLineNo() { return lineNo; }
        public String getMessage() { return message; }
    }

    /**
     * 导入报告。预检(dryRun)与落库共用同一个模型,前端渲染也共用一套。
     *
     * {@code totalErrors} 必须与 {@code errors.size()} 分开记:明细只保留前
     * {@link #MAX_ERRORS} 条,但总数要如实报 —— 否则用户以为"就 50 个错",
     * 改完再导还是失败,来回好几轮。
     */
    public static class Report {
        private boolean ok;
        private boolean dryRun;
        private String encoding;
        private int fileRows;
        private int totalErrors;
        private final List<RowError> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        /**
         * 预览:让用户核对"每一行被解析成了什么"。
         *
         * 列名传的是**key**(如 {@code "sku"} / {@code "contact"}),不是中文 ——
         * 由前端映射到 i18n,英文界面才不会冒出中文表头。
         *
         * ⚠️ 这两个字段放在**基类**而不是各模块的 ImportReport 子类里:四个模块要的
         * 是同一个渲染,各写一份的话前端得跟着写四套。子类**不要**再定义同名的
         * getter —— Jackson 会因为 "Conflicting getter definitions" 在启动时直接失败。
         */
        private List<String> previewColumns;
        private final List<PreviewRow> lines = new ArrayList<>();

        /** 声明预览列(只需调一次) */
        public void preview(String... columnKeys) {
            this.previewColumns = List.of(columnKeys);
        }

        /** 追加一条预览行;超过 {@link ImportSupport#MAX_PREVIEW_LINES} 直接丢弃 */
        public void previewLine(int lineNo, Object... cells) {
            if (lines.size() >= MAX_PREVIEW_LINES) return;
            lines.add(new PreviewRow(lineNo, cells));
        }

        public List<String> getPreviewColumns() { return previewColumns; }
        public List<PreviewRow> getLines() { return lines; }

        public void error(int line, String msg) {
            totalErrors++;
            if (errors.size() < MAX_ERRORS) errors.add(new RowError(line, msg));
        }
        public void warn(String msg) { warnings.add(msg); }

        public boolean isOk() { return ok; }
        public void setOk(boolean v) { this.ok = v; }
        public boolean isDryRun() { return dryRun; }
        public void setDryRun(boolean v) { this.dryRun = v; }
        public String getEncoding() { return encoding; }
        public void setEncoding(String v) { this.encoding = v; }
        public int getFileRows() { return fileRows; }
        public void setFileRows(int v) { this.fileRows = v; }
        public int getTotalErrors() { return totalErrors; }
        public List<RowError> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }
        public boolean hasErrors() { return totalErrors > 0; }
    }

    // ==================== 近似名 ====================

    /** 预览行:文件行号 + 与 previewColumns 一一对应的单元格值 */
    public static class PreviewRow {
        private final int lineNo;
        private final List<Object> cells;
        public PreviewRow(int lineNo, Object[] cells) {
            this.lineNo = lineNo;
            this.cells = cells == null ? new ArrayList<>() : new ArrayList<>(java.util.Arrays.asList(cells));
        }
        public int getLineNo() { return lineNo; }
        public List<Object> getCells() { return cells; }
    }

    /**
     * 编辑距离 ≤ 2 的快速判定(早退,不建完整矩阵)。
     * 用于"供应商名称打错一个字"这类提示 —— 归一化后的完全一致用 equals,不必走这里。
     */
    public static boolean nearName(String a, String b) {
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
            if (rowMin > 2) return false;
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()] <= 2;
    }

    // ==================== 解析小工具 ====================

    public static Integer parseInt(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            String t = s.trim();
            if (t.endsWith(".0")) t = t.substring(0, t.length() - 2);   // Excel 导出的 "5.0"
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static java.math.BigDecimal parseDecimal(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new java.math.BigDecimal(s.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 「启用/停用」「是/否」「1/0」「true/false」→ Boolean;空返回 null(由调用方决定默认值) */
    public static Boolean parseEnabled(String s) {
        if (s == null || s.isBlank()) return null;
        String t = norm(s);
        if (t.equals("启用") || t.equals("是") || t.equals("1") || t.equals("TRUE") || t.equals("Y") || t.equals("YES")) {
            return Boolean.TRUE;
        }
        if (t.equals("停用") || t.equals("禁用") || t.equals("否") || t.equals("0")
                || t.equals("FALSE") || t.equals("N") || t.equals("NO")) {
            return Boolean.FALSE;
        }
        return null;
    }
}
