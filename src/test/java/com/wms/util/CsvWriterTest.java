package com.wms.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSV 导出。与 {@link CsvTest}(单字段转义)、{@link CsvParserTest}(反方向解析)配套。
 *
 * 这里钉三件事,每件都对应过一次现场问题:
 *  1) **BOM**:没有它,Excel 双击打开中文表头就是乱码;
 *  2) **列数对齐**:表头 5 列而行只有 3 个值时,不能把后面的列错位;
 *  3) **转义**:含逗号/引号/公式开头的值必须过 {@link Csv#esc},否则导出的文件
 *     再导回来就散架了(而"导出改一改再导入"正是最常用的用法)。
 */
class CsvWriterTest {

    @Test
    void startsWithUtf8Bom() {
        byte[] out = CsvWriter.toCsv(new String[]{"A"}, List.of());
        assertEquals((byte) 0xEF, out[0]);
        assertEquals((byte) 0xBB, out[1]);
        assertEquals((byte) 0xBF, out[2]);
    }

    @Test
    void headerAndRows_inOrder() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"1", "螺丝刀"});
        String s = new String(CsvWriter.toCsv(new String[]{"ID", "名称"}, rows), StandardCharsets.UTF_8);
        assertEquals("﻿ID,名称\n1,螺丝刀\n", s);
    }

    @Test
    void nullValue_becomesEmptyCell() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"1", null});
        String s = new String(CsvWriter.toCsv(new String[]{"A", "B"}, rows), StandardCharsets.UTF_8);
        assertEquals("﻿A,B\n1,\n", s);
    }

    @Test
    void commaInValue_isQuoted() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"戴尔,生物科技"});
        String s = new String(CsvWriter.toCsv(new String[]{"H"}, rows), StandardCharsets.UTF_8);
        assertTrue(s.contains("\"戴尔,生物科技\""));
    }

    @Test
    void formulaPrefix_isEscaped() {
        // =1+1 这类值不加前缀的话,Excel 打开会当公式执行
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"=1+1"});
        String s = new String(CsvWriter.toCsv(new String[]{"H"}, rows), StandardCharsets.UTF_8);
        assertTrue(s.contains("'=1+1"));
    }

    @Test
    void rowsCanBeReParsed() {
        // 导出 → 解析 必须闭环(用户经常导出后改一改再导入)
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"A,1", "带\"引号", "多行\n值"});
        byte[] out = CsvWriter.toCsv(new String[]{"X", "Y", "Z"}, rows);

        CsvParser.Parsed parsed = CsvParser.decode(out);
        assertEquals(List.of("X", "Y", "Z"), parsed.rows().get(0).cells());
        assertEquals(List.of("A,1", "带\"引号", "多行\n值"), parsed.rows().get(1).cells());
    }

    @Test
    void emptyRows_onlyHeader() {
        String s = new String(CsvWriter.toCsv(new String[]{"A"}, List.of()), StandardCharsets.UTF_8);
        assertEquals("﻿A\n", s);
    }
}
