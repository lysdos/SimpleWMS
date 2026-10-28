package com.wms.service;

import com.wms.dao.LocationDao;
import com.wms.dao.ProductDao;
import com.wms.dao.SupplierDao;
import com.wms.model.InboundOrder;
import com.wms.model.Location;
import com.wms.model.Product;
import com.wms.model.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 入库 CSV 导入的解析与预检。
 *
 * 这里刻意**不测**真正的落库(那是 InboundService 的既有路径,单独有测试),重点钉三件事:
 *  1) dryRun **一次都不碰写入**(导入最怕的是一次预检就把占位单元造了出来);
 *  2) 出错时**报的文件行号**与用户数的一致(错位就没人能按提示改对);
 *  3) 合并计数与落库口径同源 —— 报告说"合并后 N 条",实际就得建 N 条。
 *
 * 项目没有测试库(见 OutboundServiceAddItemTest 的说明),所以这里全是 mock。
 */
@ExtendWith(MockitoExtension.class)
class InboundImportServiceTest {

    @Mock private InboundService inboundService;
    @Mock private ProductDao productDao;
    @Mock private LocationDao locationDao;
    @Mock private SupplierDao supplierDao;

    private InboundImportService service;

    @BeforeEach
    void setUp() {
        // 三个 DAO 的 findAll 只在 analyze() 里用;纯静态工具方法(parseDate / norm)
        // 不碰它们。为了让直接测静态方法的用例不触发 Mockito 的 UnnecessaryStubbing,
        // 这里用 lenient()。
        lenient().when(productDao.findAll(isNull(), isNull())).thenReturn(List.of(product(1, "SKU-A", "螺丝刀")));
        lenient().when(locationDao.findAll(isNull(), isNull(), isNull())).thenReturn(List.of(
                location("A-01-01", true), location("B-02-02", true), location("C-03-03", false)));
        lenient().when(supplierDao.findAll(isNull(), isNull())).thenReturn(List.of(supplier("戴尔")));
        service = new InboundImportService(inboundService, productDao, locationDao, supplierDao, 10000);
    }

    // ---------- 正常路径 ----------

    @Test
    void dryRun_reportsAndNeverWrites() {
        var r = service.importInbound(csv("SKU,数量\nSKU-A,3\n"), null, null, "tester", true);

        assertTrue(r.isOk());
        assertTrue(r.isDryRun());
        assertEquals(1, r.getFileRows());
        assertEquals(1, r.getMergedLines());
        assertEquals(3, r.getTotalUnits());
        assertEquals("SKU-A", r.getLines().get(0).getSku());
        // 核心断言:预检一行都不许落库
        verifyNoInteractions(inboundService);
    }

    @Test
    void confirmedImport_callsDoInboundBatchOnce() {
        InboundOrder order = new InboundOrder();
        order.setId(9); order.setOrderNo("IN-1");
        when(inboundService.doInboundBatch(any(), anyString(), anyString(), anyString())).thenReturn(order);

        var r = service.importInbound(csv("SKU,数量\nSKU-A,2\n"), "EXT-1", "备注", "tester", false);

        assertTrue(r.isOk());
        assertEquals(9, r.getOrderId());
        assertEquals("IN-1", r.getOrderNo());
        verify(inboundService).doInboundBatch(any(), any(), any(), any());
    }

    @Test
    void errors_blockWrite() {
        var r = service.importInbound(csv("SKU,数量\nNOPE,1\n"), null, null, "t", false);

        assertFalse(r.isOk());
        assertEquals(1, r.getErrors().size());
        // 有错就不许建单
        verify(inboundService, never()).doInboundBatch(any(), any(), any(), any());
    }

    // ---------- SKU 匹配(大小写 / 空格 / 全角) ----------

    @Test
    void skuMatch_isCaseAndWhitespaceInsensitive() {
        // 库的排序规则是 utf8mb4_unicode_ci(大小写不敏感),Java Map 却敏感 ——
        // 不做归一化就会"商品明明存在却说 SKU 不存在"
        var r = service.importInbound(csv("SKU,数量\n sku-a ,1\n"), null, null, "t", true);
        assertTrue(r.isOk(), () -> "errors=" + r.getErrors());
        assertEquals(1, r.getTotalUnits());
    }

    @Test
    void unknownSku_reportsFileLineNumber() {
        var r = service.importInbound(csv("SKU,数量\nSKU-A,1\nNOPE,2\n"), null, null, "t", true);

        assertFalse(r.isOk());
        assertEquals(3, r.getErrors().get(0).getLineNo());   // 第 1 行是表头
        assertTrue(r.getErrors().get(0).getMessage().contains("NOPE"));
    }

    // ---------- 数量 ----------

    @Test
    void invalidQuantity_rejected() {
        for (String q : new String[]{"0", "-1", "abc", ""}) {
            var r = service.importInbound(csv("SKU,数量\nSKU-A," + q + "\n"), null, null, "t", true);
            assertFalse(r.isOk(), "数量非法应被拒: [" + q + "]");
        }
    }

    @Test
    void excelStyleDecimal_quantityAccepted() {
        // Excel 导出数字时常见 "3.0"
        var r = service.importInbound(csv("SKU,数量\nSKU-A,3.0\n"), null, null, "t", true);
        assertTrue(r.isOk());
        assertEquals(3, r.getTotalUnits());
    }

    // ---------- 库位 ----------

    @Test
    void unknownLocation_rejected() {
        var r = service.importInbound(csv("SKU,数量,库位\nSKU-A,1,A-99-99\n"), null, null, "t", true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("库位不存在"));
    }

    @Test
    void disabledLocation_rejected() {
        var r = service.importInbound(csv("SKU,数量,库位\nSKU-A,1,C-03-03\n"), null, null, "t", true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("已停用"));
    }

    @Test
    void blankLocation_allowed_usesProductDefault() {
        var r = service.importInbound(csv("SKU,数量,库位\nSKU-A,1,\n"), null, null, "t", true);
        assertTrue(r.isOk());
        assertNull(r.getLines().get(0).getLocation());
    }

    // ---------- 日期 ----------

    @Test
    void dateFormats_allAccepted() {
        LocalDate expect = LocalDate.of(2026, 9, 1);
        for (String d : new String[]{"2026-09-01", "2026/9/1", "2026.9.1", "20260901"}) {
            assertEquals(expect, InboundImportService.parseDate(d), "日期格式: " + d);
        }
    }

    @Test
    void excelSerialDate_accepted() {
        // 46266 = 2026-09-01(Excel 1900 起算)
        assertEquals(LocalDate.of(2026, 9, 1), InboundImportService.parseDate("46266"));
    }

    @Test
    void badDate_rejected() {
        var r = service.importInbound(csv("SKU,数量,生产日期\nSKU-A,1,不是日期\n"), null, null, "t", true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("生产日期"));
    }

    // ---------- 合并计数(与落库同源) ----------

    @Test
    void sameBatchLines_areMerged_andCountedOnce() {
        // 两行完全同批次 → 合并成 1 条、数量相加
        var r = service.importInbound(csv("SKU,数量,供应商\nSKU-A,2,戴尔\nSKU-A,3,戴尔\n"), null, null, "t", true);
        assertTrue(r.isOk());
        assertEquals(2, r.getFileRows());
        assertEquals(1, r.getMergedLines());
        assertEquals(5, r.getTotalUnits());
        assertEquals(2, r.getLines().get(0).getSourceLines().size());
    }

    @Test
    void differentProductionDate_isNotMerged() {
        // 同 SKU 不同生产日期 = 两个批次,不能合并
        var r = service.importInbound(
                csv("SKU,数量,生产日期\nSKU-A,1,2026-09-01\nSKU-A,1,2026-09-02\n"), null, null, "t", true);
        assertTrue(r.isOk());
        assertEquals(2, r.getMergedLines());
    }

    @Test
    void differentLocation_isNotMerged() {
        var r = service.importInbound(
                csv("SKU,数量,库位\nSKU-A,1,A-01-01\nSKU-A,1,B-02-02\n"), null, null, "t", true);
        assertTrue(r.isOk());
        assertEquals(2, r.getMergedLines());
    }

    // ---------- 上限 ----------

    @Test
    void overUnitLimit_rejected() {
        var small = new InboundImportService(inboundService, productDao, locationDao, supplierDao, 5);
        var r = small.importInbound(csv("SKU,数量\nSKU-A,10\n"), null, null, "t", true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("上限"));
    }

    // ---------- 表头 ----------

    @Test
    void missingHeader_rejectedWithHint() {
        var r = service.importInbound(csv("随便,写\n1,2\n"), null, null, "t", true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("表头"));
    }

    @Test
    void headerAfterTitleLines_isFound() {
        // 用户常常在上面加一行"XX公司到货清单"
        var r = service.importInbound(csv("某公司到货清单,,,,\nSKU,数量\nSKU-A,2\n"), null, null, "t", true);
        assertTrue(r.isOk());
        assertEquals(1, r.getFileRows());
    }

    @Test
    void columnOrderArbitrary_andExtraColumnsIgnored() {
        var r = service.importInbound(csv("数量,无关列,SKU\n2,zzz,SKU-A\n"), null, null, "t", true);
        assertTrue(r.isOk());
        assertEquals(2, r.getTotalUnits());
    }

    @Test
    void headerOnly_noData_error() {
        var r = service.importInbound(csv("SKU,数量\n"), null, null, "t", true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("没有数据行"));
    }

    @Test
    void blankRowsAreIgnored() {
        var r = service.importInbound(csv("SKU,数量\n\nSKU-A,1\n\n"), null, null, "t", true);
        assertTrue(r.isOk());
        assertEquals(1, r.getFileRows());
    }

    @Test
    void manyErrors_areCappedButTotalIsReported() {
        // 明细只保留前 50 条,但总数必须如实报 —— 否则用户改完看到的 50 条再导还是失败,来回好几轮
        StringBuilder sb = new StringBuilder("SKU,数量\n");
        for (int i = 0; i < 80; i++) sb.append("NOPE-").append(i).append(",1\n");

        var r = service.importInbound(csv(sb.toString()), null, null, "t", true);

        assertFalse(r.isOk());
        assertEquals(50, r.getErrors().size(), "明细条数应有上限");
        assertEquals(80, r.getTotalErrors(), "总数必须如实报告,不能只说 50");
    }

    // ---------- 供应商警告 ----------

    @Test
    void supplierNotInArchive_warnsButAllows() {
        var r = service.importInbound(csv("SKU,数量,供应商\nSKU-A,1,完全不存在的公司\n"), null, null, "t", true);
        assertTrue(r.isOk(), "供应商不在档案只警告,不阻断");
        assertFalse(r.getWarnings().isEmpty());
        assertTrue(r.getWarnings().get(0).contains("不在档案中"));
    }

    @Test
    void supplierSimilarToArchive_warnsWithSuggestion() {
        // 「戴尔 」多一个空格应被识别为"档案里是「戴尔」"
        var r = service.importInbound(csv("SKU,数量,供应商\nSKU-A,1,戴尔 \n"), null, null, "t", true);
        // 归一化后完全相同 → 不应产生任何警告
        assertTrue(r.getWarnings().isEmpty(), "归一化后一致的供应商不该报警告: " + r.getWarnings());
    }

    @Test
    void supplierTypo_warnsWithSuggestion() {
        var r = service.importInbound(csv("SKU,数量,供应商\nSKU-A,1,戴而你\n"), null, null, "t", true);
        assertTrue(r.isOk());
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("戴尔")),
                "近似名应提示档案里的写法: " + r.getWarnings());
    }

    // ---------- GBK ----------

    @Test
    void gbkFile_parsedCorrectly() {
        byte[] gbk = "SKU,数量\nSKU-A,2\n".getBytes(java.nio.charset.Charset.forName("GBK"));
        var r = service.importInbound(gbk, null, null, "t", true);
        assertTrue(r.isOk());
        assertEquals("GBK", r.getEncoding());
    }

    // ---------- helpers ----------

    private static byte[] csv(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Product product(int id, String sku, String name) {
        Product p = new Product();
        p.setId(id); p.setSku(sku); p.setName(name);
        return p;
    }

    private static Location location(String code, boolean enabled) {
        Location l = new Location();
        l.setCode(code); l.setName(code); l.setEnabled(enabled);
        return l;
    }

    private static Supplier supplier(String name) {
        Supplier s = new Supplier();
        s.setName(name);
        return s;
    }
}
