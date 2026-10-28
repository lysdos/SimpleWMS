package com.wms.service;

import com.wms.dao.LocationDao;
import com.wms.dao.ProductDao;
import com.wms.dao.SupplierDao;
import com.wms.model.Location;
import com.wms.model.Product;
import com.wms.model.ProductSupplier;
import com.wms.model.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 商品 CSV 导入的解析与预检。
 *
 * 重点钉三件事:
 *  1) dryRun **一次都不碰写入**;
 *  2) 「数量」列被忽略时必须**明确告知** —— 用户可能拿导出文件改一改就来导,
 *     不提示的话他会以为库存数量导进去了;
 *  3) 供应商按名称匹配,匹配不上只警告不阻断(与该模块既有口径一致)。
 */
@ExtendWith(MockitoExtension.class)
class ProductImportServiceTest {

    @Mock private ProductService productService;
    @Mock private ProductDao productDao;
    @Mock private LocationDao locationDao;
    @Mock private SupplierDao supplierDao;

    private ProductImportService service;

    @BeforeEach
    void setUp() {
        lenient().when(productDao.findAll(isNull(), isNull())).thenReturn(List.of(product(1, "SKU-A", "螺丝刀")));
        lenient().when(locationDao.findAll(isNull(), isNull(), isNull())).thenReturn(List.of(
                location("A-01-01", true), location("C-03-03", false)));
        lenient().when(supplierDao.findAll(isNull(), isNull())).thenReturn(List.of(supplier(10, "戴尔")));
        service = new ProductImportService(productService, productDao, locationDao, supplierDao);
    }

    // ---------- 正常 ----------

    @Test
    void dryRun_neverWrites() {
        var r = service.importProducts(csv("SKU,名称\nNEW-1,新商品\n"), true);

        assertTrue(r.isOk(), () -> "errors=" + r.getErrors());
        assertEquals(1, r.getFileRows());
        assertEquals(0, r.getCreated());
        verifyNoInteractions(productService);
    }

    @Test
    void confirm_createsEachRow() {
        var r = service.importProducts(csv("SKU,名称\nNEW-1,新商品\nNEW-2,另一个\n"), false);

        assertTrue(r.isOk(), () -> "errors=" + r.getErrors());
        assertEquals(2, r.getCreated());
        verify(productService, org.mockito.Mockito.times(2)).create(any(Product.class));
    }

    @Test
    void errors_blockWrite() {
        var r = service.importProducts(csv("SKU,名称\n,缺SKU\n"), false);

        assertFalse(r.isOk());
        verify(productService, never()).create(any());
    }

    // ---------- 数量列 ----------

    @Test
    void quantityColumn_presentIsWarned() {
        // 用户拿导出文件改一改再来导是最常见的用法,必须明确告知数量会被忽略
        var r = service.importProducts(csv("SKU,名称,数量\nNEW-1,新商品,100\n"), true);

        assertTrue(r.isOk(), () -> "errors=" + r.getErrors());
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("数量")),
                "有「数量」列时必须警告: " + r.getWarnings());
    }

    @Test
    void quantityColumn_absentNoWarning() {
        var r = service.importProducts(csv("SKU,名称\nNEW-1,新商品\n"), true);

        assertTrue(r.isOk());
        assertTrue(r.getWarnings().isEmpty(), "没有「数量」列不该提示: " + r.getWarnings());
    }

    @Test
    void quantityIsAlwaysZero_evenIfFileHasValue() {
        var r = service.importProducts(csv("SKU,名称,数量\nNEW-1,新商品,100\n"), false);
        assertTrue(r.isOk());

        ArgumentCaptor<Product> cap = ArgumentCaptor.forClass(Product.class);
        verify(productService).create(cap.capture());
        assertEquals(0, cap.getValue().getQuantity(), "导入不能把数量写进商品资料");
    }

    // ---------- 唯一性 ----------

    @Test
    void existingSku_rejected() {
        var r = service.importProducts(csv("SKU,名称\nSKU-A,重复\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("SKU 已存在"));
    }

    @Test
    void duplicateSkuInFile_rejected() {
        var r = service.importProducts(csv("SKU,名称\nNEW-1,一\nnew-1,二\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.getMessage().contains("文件中重复")));
    }

    @Test
    void skuMatch_isCaseInsensitive() {
        // 库的排序规则 utf8mb4_unicode_ci 大小写不敏感,Java Map 敏感 ——
        // 不归一化就会"商品明明存在却说可以新建",最后撞唯一索引报库错误
        var r = service.importProducts(csv("SKU,名称\nsku-a,小写重复\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("SKU 已存在"));
    }

    // ---------- 库位 ----------

    @Test
    void unknownLocation_rejected() {
        var r = service.importProducts(csv("SKU,名称,默认库位\nNEW-1,x,A-99-99\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("默认库位不存在"));
    }

    @Test
    void disabledLocation_rejected() {
        var r = service.importProducts(csv("SKU,名称,默认库位\nNEW-1,x,C-03-03\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("已停用"));
    }

    // ---------- 供应商 ----------

    @Test
    void supplierMatchedByName_linked() {
        var r = service.importProducts(csv("SKU,名称,全部供应商\nNEW-1,x,戴尔\n"), false);
        assertTrue(r.isOk(), () -> "errors=" + r.getErrors());

        ArgumentCaptor<Product> cap = ArgumentCaptor.forClass(Product.class);
        verify(productService).create(cap.capture());
        List<ProductSupplier> links = cap.getValue().getSuppliersAsList();
        assertNotNull(links);
        assertEquals(1, links.size());
        assertEquals(10, links.get(0).getSupplierId());
    }

    @Test
    void supplierNotFound_warnsAndSkips() {
        var r = service.importProducts(csv("SKU,名称,全部供应商\nNEW-1,x,不存在的公司\n"), false);

        assertTrue(r.isOk(), "供应商不存在只警告不阻断");
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("不在供应商档案")));
        // 一个都没匹配上 → 关联为 null(不写),避免清空原有关系
        ArgumentCaptor<Product> cap = ArgumentCaptor.forClass(Product.class);
        verify(productService).create(cap.capture());
        assertNull(cap.getValue().getSuppliersAsList());
    }

    @Test
    void defaultSupplierNotInAllList_isMergedIn() {
        var r = service.importProducts(
                csv("SKU,名称,默认供应商,全部供应商\nNEW-1,x,戴尔,戴尔\n"), false);
        assertTrue(r.isOk(), () -> "errors=" + r.getErrors());

        ArgumentCaptor<Product> cap = ArgumentCaptor.forClass(Product.class);
        verify(productService).create(cap.capture());
        List<ProductSupplier> links = cap.getValue().getSuppliersAsList();
        assertEquals(1, links.size(), "默认项与全部列表重复时应只留一条");
        assertTrue(links.get(0).getIsDefault());
    }

    @Test
    void noSupplierColumns_leavesLinksNull() {
        var r = service.importProducts(csv("SKU,名称\nNEW-1,x\n"), false);
        assertTrue(r.isOk());

        ArgumentCaptor<Product> cap = ArgumentCaptor.forClass(Product.class);
        verify(productService).create(cap.capture());
        // null = 不动关联(ProductService 的既有语义)
        assertNull(cap.getValue().getSuppliersAsList());
    }

    // ---------- 表头 ----------

    @Test
    void missingHeader_rejected() {
        var r = service.importProducts(csv("随便,写\n1,2\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("表头"));
    }

    @Test
    void columnOrderArbitrary() {
        var r = service.importProducts(csv("名称,SKU\n新商品,NEW-1\n"), true);
        assertTrue(r.isOk(), () -> "errors=" + r.getErrors());
    }

    @Test
    void headerAfterTitleRow_found() {
        var r = service.importProducts(csv("某公司商品清单\nSKU,名称\nNEW-1,x\n"), true);
        assertTrue(r.isOk());
        assertEquals(1, r.getFileRows());
    }

    @Test
    void blankRowsIgnored() {
        var r = service.importProducts(csv("SKU,名称\n\nNEW-1,x\n\n"), true);
        assertTrue(r.isOk());
        assertEquals(1, r.getFileRows());
    }

    @Test
    void gbkFile_parsed() {
        byte[] gbk = "SKU,名称\nNEW-1,中文名称\n".getBytes(Charset.forName("GBK"));
        var r = service.importProducts(gbk, true);
        assertTrue(r.isOk());
        assertEquals("GBK", r.getEncoding());
    }

    // ---------- helpers ----------

    private static byte[] csv(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static Product product(int id, String sku, String name) {
        Product p = new Product(); p.setId(id); p.setSku(sku); p.setName(name); return p;
    }
    private static Location location(String code, boolean enabled) {
        Location l = new Location(); l.setCode(code); l.setName(code); l.setEnabled(enabled); return l;
    }
    private static Supplier supplier(int id, String name) {
        Supplier s = new Supplier(); s.setId(id); s.setName(name); return s;
    }
}
