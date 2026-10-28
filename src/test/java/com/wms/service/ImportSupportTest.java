package com.wms.service;

import com.wms.util.CsvParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基础管理五模块导入的共用件。
 *
 * 表头定位是五个模块**每一个**都依赖的第一道关,它错了整条链路都不可用 ——
 * 而它恰好有一堆容易漏的边界(标题行、列序、别名、缺列、全角空格)。
 * 所以单独钉一遍,而不是靠各模块的导入测试间接覆盖。
 */
class ImportSupportTest {

    private static final Map<String, List<String>> ALIASES = Map.of(
            "sku",   List.of("SKU", "商品编码"),
            "name",  List.of("名称", "商品名称"),
            "price", List.of("单价")
    );
    private static final List<String> REQUIRED = List.of("sku", "name");

    // ---------- 表头定位 ----------

    @Test
    void findsHeaderAtFirstRow() {
        var rows = parse("SKU,名称,单价\nA1,螺丝刀,9.9\n");
        ImportSupport.Header h = ImportSupport.findHeader(rows, ALIASES, REQUIRED);
        assertNotNull(h);
        assertEquals(0, h.rowIndex());
        assertEquals(0, h.indexOf("sku"));
        assertEquals(1, h.indexOf("name"));
        assertEquals(2, h.indexOf("price"));
    }

    @Test
    void findsHeaderAfterTitleRows() {
        // 用户常常在上面加一行"XX公司商品清单"
        var rows = parse("某公司商品清单\nSKU,名称\nA1,x\n");
        ImportSupport.Header h = ImportSupport.findHeader(rows, ALIASES, REQUIRED);
        assertNotNull(h);
        assertEquals(1, h.rowIndex());
    }

    @Test
    void givesUpBeyondTenRows() {
        // 前 10 行内找不到就当没有 —— 否则一个大文件会被从头扫到尾
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 11; i++) sb.append("废话").append(i).append('\n');
        sb.append("SKU,名称\nA1,x\n");
        assertNull(ImportSupport.findHeader(parse(sb.toString()), ALIASES, REQUIRED));
    }

    @Test
    void columnOrderArbitrary() {
        var rows = parse("名称,SKU\n螺丝刀,A1\n");
        ImportSupport.Header h = ImportSupport.findHeader(rows, ALIASES, REQUIRED);
        assertNotNull(h);
        assertEquals(1, h.indexOf("sku"));
        assertEquals(0, h.indexOf("name"));
    }

    @Test
    void acceptsAliases() {
        var rows = parse("商品编码,商品名称\nA1,x\n");
        assertNotNull(ImportSupport.findHeader(rows, ALIASES, REQUIRED));
    }

    @Test
    void missingRequiredColumn_rejected() {
        // 只有 SKU 没有名称 → 不认作表头
        assertNull(ImportSupport.findHeader(parse("SKU,单价\nA1,9.9\n"), ALIASES, REQUIRED));
    }

    @Test
    void extraColumnsIgnored() {
        var rows = parse("SKU,名称,无关列,另一个\nA1,x,y,z\n");
        ImportSupport.Header h = ImportSupport.findHeader(rows, ALIASES, REQUIRED);
        assertNotNull(h);
        assertFalse(h.has("无关列"));
        assertEquals(0, h.indexOf("sku"));
    }

    @Test
    void fullWidthBracketsAndSpacesTolerated() {
        // 用户从 Word/微信里粘出来的表头常带全角空格
        var rows = parse("SKU ,　名称　\nA1,x\n");
        assertNotNull(ImportSupport.findHeader(rows, ALIASES, REQUIRED));
    }

    // ---------- cell ----------

    @Test
    void cell_outOfRangeIsEmpty() {
        List<String> cells = List.of("a");
        assertEquals("", ImportSupport.cell(cells, null));
        assertEquals("", ImportSupport.cell(cells, 5));
        assertEquals("", ImportSupport.cell(cells, -1));
        assertEquals("a", ImportSupport.cell(cells, 0));
    }

    @Test
    void cell_trims() {
        assertEquals("x", ImportSupport.cell(List.of("  x  "), 0));
    }

    // ---------- norm ----------

    @Test
    void norm_removesSpacesAndUppercasesAndFoldsWidth() {
        assertEquals("ABC", ImportSupport.norm(" a b c "));
        assertEquals("ABC", ImportSupport.norm("ＡＢＣ"));       // 全角
        assertEquals("戴尔", ImportSupport.norm(" 戴尔 "));
        assertEquals("", ImportSupport.norm(null));
    }

    // ---------- 报告 ----------

    @Test
    void report_capsDetailButKeepsTotal() {
        // 明细只留 50 条,但总数必须如实 —— 否则用户以为改完看到的就好,再导还是失败
        ImportSupport.Report r = new ImportSupport.Report();
        for (int i = 0; i < 80; i++) r.error(i + 2, "第 " + i + " 个错");

        assertEquals(ImportSupport.MAX_ERRORS, r.getErrors().size());
        assertEquals(80, r.getTotalErrors());
        assertTrue(r.hasErrors());
    }

    @Test
    void report_warningsDoNotAffectOk() {
        ImportSupport.Report r = new ImportSupport.Report();
        r.warn("一条警告");
        assertFalse(r.hasErrors(), "警告不该让导入失败");
    }

    // ---------- 解析工具 ----------

    @Test
    void parseInt_handlesExcelDecimals() {
        assertEquals(5, ImportSupport.parseInt("5"));
        assertEquals(5, ImportSupport.parseInt("5.0"));      // Excel 常导出 "5.0"
        assertEquals(5, ImportSupport.parseInt(" 5 "));
        assertNull(ImportSupport.parseInt("abc"));
        assertNull(ImportSupport.parseInt(""));
        assertNull(ImportSupport.parseInt(null));
    }

    @Test
    void parseDecimal_stripsThousandsSeparator() {
        assertEquals(new java.math.BigDecimal("1234.56"), ImportSupport.parseDecimal("1,234.56"));
        assertEquals(new java.math.BigDecimal("9.9"), ImportSupport.parseDecimal("9.9"));
        assertNull(ImportSupport.parseDecimal("abc"));
    }

    @Test
    void parseEnabled_acceptsCommonSpellings() {
        for (String s : new String[]{"启用", "是", "1", "true", "Y"}) {
            assertEquals(Boolean.TRUE, ImportSupport.parseEnabled(s), "应识别为启用: " + s);
        }
        for (String s : new String[]{"停用", "禁用", "否", "0", "false", "N"}) {
            assertEquals(Boolean.FALSE, ImportSupport.parseEnabled(s), "应识别为停用: " + s);
        }
        assertNull(ImportSupport.parseEnabled("随便"));
        assertNull(ImportSupport.parseEnabled(""));
    }

    @Test
    void nearName_detectsTypo() {
        assertTrue(ImportSupport.nearName("戴尔公司", "戴尔公可"));   // 错一个字
        assertTrue(ImportSupport.nearName("戴尔", "戴尔公司"));       // 多两个字
        assertFalse(ImportSupport.nearName("戴尔", "完全不同的名字"));
    }

    // ---------- helper ----------

    private static List<CsvParser.Row> parse(String s) {
        return CsvParser.parse(s);
    }
}
