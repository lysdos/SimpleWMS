package com.wms.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CSV 字段转义。两件事都要钉住:
 *
 *  - RFC 4180 的引号规则:含逗号 / 引号 / 换行才包引号,内部引号翻倍;
 *  - 公式注入前缀:Excel 会把 {@code = + - @} 及制表符 / 回车开头的单元格当**公式**执行,
 *    加引号挡不住(引号只解决分隔符歧义)—— 这些值前加一个单引号;但**纯数字不加**,
 *    否则「-5」会从数字变成文本。
 *
 * 这套规则原先在 4 个 controller 里各抄一份(合并成 {@link Csv} 时顺手加了公式前缀),
 * 所以这里既验引号、也验前缀。
 */
class CsvTest {

    @Test
    void null_becomesEmpty() {
        assertEquals("", Csv.esc(null));
    }

    @Test
    void plainText_untouched() {
        assertEquals("戴尔", Csv.esc("戴尔"));
        assertEquals("A-01-01", Csv.esc("A-01-01"));
    }

    @Test
    void containingCommaOrQuoteOrNewline_isQuoted() {
        assertEquals("\"戴尔,生物科技\"", Csv.esc("戴尔,生物科技"));
        assertEquals("\"a\"\"b\"", Csv.esc("a\"b"));
        assertEquals("\"a\nb\"", Csv.esc("a\nb"));
    }

    @Test
    void formulaStart_getsApostrophePrefix() {
        assertEquals("'=1+1", Csv.esc("=1+1"));
        assertEquals("'@cmd", Csv.esc("@cmd"));
        assertEquals("'-2+3+cmd", Csv.esc("-2+3+cmd"));
        // 两条规则叠加:先加前缀,再因为含逗号而包引号
        assertEquals("\"'=1,2\"", Csv.esc("=1,2"));
    }

    @Test
    void plainNumbers_areNotPrefixed() {
        assertEquals("-5", Csv.esc("-5"));
        assertEquals("+3.2", Csv.esc("+3.2"));
        assertEquals("12", Csv.esc(12));
    }
}
