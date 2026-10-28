package com.wms.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSV 解析。与 {@link CsvTest}(导出转义)对称,但这里钉的是**读**回来的行为。
 *
 * 重点是三件在真实现场会让功能"看起来坏了"的事:
 *  1) BOM 没剥 → 第一个表头变成 "﻿SKU",按列名匹配永远对不上;
 *  2) GBK 文件按 UTF-8 硬解 → 中文全乱码,报"SKU 不存在",比报错更难查;
 *  3) 行号错位 → 预检说"第 7 行有错",用户数过去发现是第 6 行。
 */
class CsvParserTest {

    // ---------- 编码 ----------

    @Test
    void utf8Bom_isStripped() {
        byte[] body = "SKU,数量\nA1,2\n".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF; withBom[1] = (byte) 0xBB; withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        CsvParser.Parsed p = CsvParser.decode(withBom);
        assertEquals("UTF-8", p.encoding());
        // 不剥 BOM 的话这里是 "﻿SKU",下面这条断言就是它存在的意义
        assertEquals("SKU", p.rows().get(0).cells().get(0));
    }

    @Test
    void gbkFile_fallsBackAndReportsEncoding() {
        // 中文 Windows 上 Excel「另存为 CSV」的默认产物
        byte[] gbk = "SKU,数量\n螺丝刀,5\n".getBytes(Charset.forName("GBK"));
        CsvParser.Parsed p = CsvParser.decode(gbk);
        assertEquals("GBK", p.encoding());
        assertEquals("螺丝刀", p.rows().get(1).cells().get(0));
    }

    @Test
    void utf8Chinese_readsAsUtf8() {
        byte[] utf8 = "SKU,数量\n螺丝刀,5\n".getBytes(StandardCharsets.UTF_8);
        CsvParser.Parsed p = CsvParser.decode(utf8);
        assertEquals("UTF-8", p.encoding());
        assertEquals("螺丝刀", p.rows().get(1).cells().get(0));
    }

    @Test
    void emptyFile_throws() {
        assertThrows(IllegalArgumentException.class, () -> CsvParser.decode(new byte[0]));
    }

    // ---------- 基本结构 ----------

    @Test
    void simpleRows() {
        List<CsvParser.Row> rows = CsvParser.parse("a,b\nc,d\n");
        assertEquals(2, rows.size());
        assertEquals(List.of("a", "b"), rows.get(0).cells());
        assertEquals(List.of("c", "d"), rows.get(1).cells());
    }

    @Test
    void crlf_and_cr_areBothAccepted() {
        assertEquals(2, CsvParser.parse("a,b\r\nc,d\r\n").size());
        assertEquals(2, CsvParser.parse("a,b\rc,d\r").size());
    }

    @Test
    void lastLineWithoutTrailingNewline_isKept() {
        List<CsvParser.Row> rows = CsvParser.parse("a,b\nc,d");
        assertEquals(2, rows.size());
        assertEquals(List.of("c", "d"), rows.get(1).cells());
    }

    // ---------- RFC 4180 引号 ----------

    @Test
    void quotedField_keepsComma() {
        List<CsvParser.Row> rows = CsvParser.parse("a,\"b,c\",d\n");
        assertEquals(List.of("a", "b,c", "d"), rows.get(0).cells());
    }

    @Test
    void doubledQuote_becomesOneQuote() {
        List<CsvParser.Row> rows = CsvParser.parse("a,\"b\"\"c\"\n");
        assertEquals(List.of("a", "b\"c"), rows.get(0).cells());
    }

    @Test
    void quotedField_keepsNewline_andLineNumberAdvances() {
        List<CsvParser.Row> rows = CsvParser.parse("a,\"x\ny\",c\nb,2,3\n");
        assertEquals(2, rows.size());
        assertEquals("x\ny", rows.get(0).cells().get(1));
        // 关键:第 2 条数据物理上在第 3 行,行号必须跟着走(取值为 1 和 3)
        assertEquals(1, rows.get(0).lineNo());
        assertEquals(3, rows.get(1).lineNo());
    }

    @Test
    void emptyFields_arePreserved() {
        List<CsvParser.Row> rows = CsvParser.parse("a,,c\n");
        assertEquals(List.of("a", "", "c"), rows.get(0).cells());
    }

    // ---------- 行号与空行 ----------

    @Test
    void lineNumbers_matchPhysicalFileLines() {
        List<CsvParser.Row> rows = CsvParser.parse("h1,h2\n\nr1,r2\n\nr3,r4\n");
        assertEquals(5, rows.size());                 // 空行也保留,由调用方决定忽略
        assertEquals(1, rows.get(0).lineNo());
        assertEquals(3, rows.get(2).lineNo());
        assertEquals(5, rows.get(4).lineNo());
    }

    @Test
    void blankRowDetection() {
        assertTrue(CsvParser.isBlankRow(List.of("", "  ", "")));
        assertTrue(CsvParser.isBlankRow(List.of()));
        assertTrue(!CsvParser.isBlankRow(List.of("", "x")));
    }

    @Test
    void overlongField_throws() {
        String huge = "x".repeat(CsvParser.MAX_FIELD_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> CsvParser.parse("a," + huge + "\n"));
    }
}
