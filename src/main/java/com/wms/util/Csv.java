package com.wms.util;

import java.util.regex.Pattern;

/**
 * 导出 CSV 时的字段转义。管两件事:
 *
 *  1. **分隔符歧义**:含逗号 / 引号 / 换行的字段用双引号包起来、内部双引号翻倍(RFC 4180)。
 *  2. **公式注入**:Excel 会把以 {@code = + - @} 或制表符 / 回车开头的单元格当**公式**执行 ——
 *     加引号挡不住(引号只解决分隔符歧义)。所以在这些字段前加一个单引号,Excel 就按文本处理。
 *     例外:纯数字(如 {@code -5}、{@code +3.2})不加 —— 它不是公式,加了反而把数字变成文本。
 *
 * 这套逻辑原先在 4 个 controller 里各抄了一份(Inbound / Outbound / StockCheck / InventoryItem),
 * 改一处漏三处是迟早的事,所以收到这里。
 */
public final class Csv {

    /** 纯数字(含正负号与小数),用来把「-5」这类正常数值排除在公式前缀之外 */
    private static final Pattern PLAIN_NUMBER = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$");

    private Csv() {}

    public static String esc(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (needsFormulaPrefix(s)) s = "'" + s;
        // 含逗号/引号/换行的,用双引号包起来,内部双引号转义
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static boolean needsFormulaPrefix(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        if (c != '=' && c != '+' && c != '-' && c != '@' && c != '\t' && c != '\r') return false;
        return !PLAIN_NUMBER.matcher(s).matches();
    }
}
