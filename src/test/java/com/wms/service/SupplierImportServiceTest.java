package com.wms.service;

import com.wms.dao.SupplierDao;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 供应商 CSV 导入。
 *
 * 重点在「名称唯一」:2026-10-28 之前 supplier 表没有唯一索引,
 * SupplierService.create 里的 DuplicateKeyException 分支是死代码,
 * 同名供应商能建两个 —— 而名称在下单弹窗里是下拉框的 value,重名会让两个选项
 * 长得一模一样、选谁都写同一个名字。导入必须在预检阶段就拦住。
 */
@ExtendWith(MockitoExtension.class)
class SupplierImportServiceTest {

    @Mock private SupplierService supplierService;
    @Mock private SupplierDao supplierDao;

    private SupplierImportService service;

    @BeforeEach
    void setUp() {
        lenient().when(supplierDao.findAll(isNull(), isNull()))
                .thenReturn(List.of(supplier(1, "戴尔")));
        service = new SupplierImportService(supplierService, supplierDao);
    }

    @Test
    void dryRun_neverWrites() {
        var r = service.importSuppliers(csv("名称\n新供应商\n"), true);

        assertTrue(r.isOk(), () -> "errors=" + r.getErrors());
        assertEquals(1, r.getFileRows());
        assertEquals(0, r.getCreated());
        verifyNoInteractions(supplierService);
    }

    @Test
    void confirm_createsRows() {
        var r = service.importSuppliers(csv("名称\n供应商甲\n供应商乙\n"), false);
        assertTrue(r.isOk(), () -> "errors=" + r.getErrors());
        assertEquals(2, r.getCreated());
        verify(supplierService, org.mockito.Mockito.times(2)).create(any(Supplier.class));
    }

    @Test
    void existingName_rejected() {
        var r = service.importSuppliers(csv("名称\n戴尔\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("已存在"));
    }

    @Test
    void duplicateNameInFile_rejected() {
        var r = service.importSuppliers(csv("名称\n甲\n甲\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.getMessage().contains("文件中重复")));
    }

    @Test
    void nameMatch_isCaseInsensitive() {
        // 排序规则 utf8mb4_unicode_ci 大小写不敏感;若只按住 Java 的 equals 判,
        // 会放过一个看起来不同、插进去却撞唯一索引的名字
        var r = service.importSuppliers(csv("名称\nABC\n"), true);
        assertTrue(r.isOk());
        lenient().when(supplierDao.findAll(isNull(), isNull())).thenReturn(List.of(supplier(2, "ABC")));
        var r2 = new SupplierImportService(supplierService, supplierDao)
                .importSuppliers(csv("名称\nabc\n"), true);
        assertFalse(r2.isOk(), "大小写不同应视为同名");
    }

    @Test
    void blankName_rejected() {
        var r = service.importSuppliers(csv("名称\n\n"), true);
        assertFalse(r.isOk());
    }

    @Test
    void overlongName_rejected() {
        var r = service.importSuppliers(csv("名称\n" + "长".repeat(129) + "\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("过长"));
    }

    @Test
    void invalidEmail_rejected() {
        var r = service.importSuppliers(csv("名称,邮箱\n甲,不是邮箱\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("邮箱"));
    }

    @Test
    void statusParsed() {
        var r = service.importSuppliers(csv("名称,状态\n甲,停用\n乙,启用\n丙,\n"), false);
        assertTrue(r.isOk(), () -> "errors=" + r.getErrors());

        ArgumentCaptor<Supplier> cap = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierService, org.mockito.Mockito.times(3)).create(cap.capture());
        List<Supplier> created = cap.getAllValues();
        assertEquals(Boolean.FALSE, created.get(0).getEnabled());
        assertEquals(Boolean.TRUE,  created.get(1).getEnabled());
        assertEquals(Boolean.TRUE,  created.get(2).getEnabled(), "留空默认启用");
    }

    @Test
    void unknownStatus_rejected() {
        var r = service.importSuppliers(csv("名称,状态\n甲,随便写的\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("状态无法识别"));
    }

    @Test
    void fieldsTrimmed() {
        var r = service.importSuppliers(csv("名称,联系人,电话\n甲, 张三 , 13800000000 \n"), false);
        assertTrue(r.isOk());

        ArgumentCaptor<Supplier> cap = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierService).create(cap.capture());
        assertEquals("甲", cap.getValue().getName());
        assertEquals("张三", cap.getValue().getContact());
        assertEquals("13800000000", cap.getValue().getPhone());
    }

    @Test
    void missingNameColumn_rejected() {
        var r = service.importSuppliers(csv("联系人,电话\n张三,123\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("表头"));
    }

    @Test
    void columnAlias_accepted() {
        var r = service.importSuppliers(csv("供应商名称\n甲公司\n"), true);
        assertTrue(r.isOk(), () -> "errors=" + r.getErrors());
    }

    @Test
    void gbkFile_parsed() {
        byte[] gbk = "名称\n中文供应商\n".getBytes(Charset.forName("GBK"));
        var r = service.importSuppliers(gbk, true);
        assertTrue(r.isOk());
        assertEquals("GBK", r.getEncoding());
    }

    @Test
    void errorsBlockWrite() {
        var r = service.importSuppliers(csv("名称\n戴尔\n甲\n"), false);
        assertFalse(r.isOk());
        verify(supplierService, never()).create(any());
    }

    private static byte[] csv(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static Supplier supplier(int id, String name) {
        Supplier s = new Supplier(); s.setId(id); s.setName(name); return s;
    }
}
