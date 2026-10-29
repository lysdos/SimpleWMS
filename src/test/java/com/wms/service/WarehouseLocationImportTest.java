package com.wms.service;

import com.wms.dao.LocationDao;
import com.wms.dao.WarehouseDao;
import com.wms.model.Location;
import com.wms.model.Warehouse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 仓库 + 库位 CSV 导入。
 *
 * 库位那条链路的独特点是「所属仓库」要能按**编码和名称两种写法**解析:
 * 库位管理页的筛选下拉显示的是仓库名称,而导出/接口里给的是编码,
 * 用户照着界面填多半写名称、照着导出文件改会写编码 —— 只认一种就会莫名其妙报
 * "所属仓库不存在"。
 */
@ExtendWith(MockitoExtension.class)
class WarehouseLocationImportTest {

    @Mock private WarehouseService warehouseService;
    @Mock private WarehouseDao warehouseDao;
    @Mock private LocationService locationService;
    @Mock private LocationDao locationDao;

    private WarehouseImportService whImport;
    private LocationImportService locImport;

    @BeforeEach
    void setUp() {
        Warehouse wh = warehouse(1, "WH-DEFAULT", "默认仓库", true);
        Warehouse disabled = warehouse(2, "WH-OFF", "停用仓库", false);

        lenient().when(warehouseDao.findAll(isNull(), isNull())).thenReturn(List.of(wh, disabled));
        lenient().when(locationDao.findAll(isNull(), isNull(), isNull())).thenReturn(List.of());

        whImport  = new WarehouseImportService(warehouseService, warehouseDao, 5000);
        locImport = new LocationImportService(locationService, locationDao, warehouseDao, 5000);
    }

    // ==================== 仓库 ====================

    @Test
    void warehouse_dryRunNeverWrites() {
        var r = whImport.importWarehouses(csv("编码,名称\nWH-NEW,新仓库\n"), true);
        assertTrue(r.isOk(), () -> "errors=" + r.getErrors());
        assertEquals(0, r.getCreated());
        verifyNoInteractions(warehouseService);
    }

    @Test
    void warehouse_confirmCreates() {
        var r = whImport.importWarehouses(csv("编码,名称\nWH-NEW,新仓库\n"), false);
        assertTrue(r.isOk(), () -> "errors=" + r.getErrors());
        assertEquals(1, r.getCreated());
        verify(warehouseService).create(any(Warehouse.class));
    }

    @Test
    void warehouse_duplicateCodeRejected() {
        var r = whImport.importWarehouses(csv("编码,名称\nWH-DEFAULT,重复\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("已存在"));
    }

    @Test
    void warehouse_duplicateInFileRejected() {
        var r = whImport.importWarehouses(csv("编码,名称\nWH-X,甲\nwh-x,乙\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.getMessage().contains("文件中重复")));
    }

    @Test
    void warehouse_blankCodeOrNameRejected() {
        assertFalse(whImport.importWarehouses(csv("编码,名称\n,只有名\n"), true).isOk());
        assertFalse(whImport.importWarehouses(csv("编码,名称\nWH-Y,\n"), true).isOk());
    }

    @Test
    void warehouse_overlongRejected() {
        var r = whImport.importWarehouses(csv("编码,名称\n" + "W".repeat(33) + ",名\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("过长"));
    }

    @Test
    void warehouse_statusParsed() {
        var r = whImport.importWarehouses(csv("编码,名称,状态\nWH-A,甲,停用\n"), false);
        assertTrue(r.isOk());
        ArgumentCaptor<Warehouse> cap = ArgumentCaptor.forClass(Warehouse.class);
        verify(warehouseService).create(cap.capture());
        assertEquals(Boolean.FALSE, cap.getValue().getEnabled());
    }

    @Test
    void warehouse_missingHeaderRejected() {
        var r = whImport.importWarehouses(csv("只有一列\n值\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("表头"));
    }

    // ==================== 库位 ====================

    @Test
    void location_dryRunNeverWrites() {
        var r = locImport.importLocations(csv("编码\nA-01-01\n"), true);
        assertTrue(r.isOk(), () -> "errors=" + r.getErrors());
        verifyNoInteractions(locationService);
    }

    @Test
    void location_confirmCreates() {
        var r = locImport.importLocations(csv("编码,名称\nA-01-01,A区\n"), false);
        assertTrue(r.isOk(), () -> "errors=" + r.getErrors());
        assertEquals(1, r.getCreated());
        verify(locationService).create(any(Location.class));
    }

    @Test
    void location_warehouseResolvedByCode() {
        var r = locImport.importLocations(csv("编码,所属仓库\nA-01,WH-DEFAULT\n"), false);
        assertTrue(r.isOk(), () -> "errors=" + r.getErrors());
        ArgumentCaptor<Location> cap = ArgumentCaptor.forClass(Location.class);
        verify(locationService).create(cap.capture());
        assertEquals(1, cap.getValue().getWarehouseId());
    }

    @Test
    void location_warehouseResolvedByName() {
        // 界面上库位筛选下拉显示的是仓库**名称**,用户照着填会写名称
        var r = locImport.importLocations(csv("编码,所属仓库\nA-02,默认仓库\n"), false);
        assertTrue(r.isOk(), () -> "errors=" + r.getErrors());
        ArgumentCaptor<Location> cap = ArgumentCaptor.forClass(Location.class);
        verify(locationService).create(cap.capture());
        assertEquals(1, cap.getValue().getWarehouseId());
    }

    @Test
    void location_unknownWarehouseRejected() {
        var r = locImport.importLocations(csv("编码,所属仓库\nA-03,不存在的仓库\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("所属仓库不存在"));
    }

    @Test
    void location_disabledWarehouseRejected() {
        var r = locImport.importLocations(csv("编码,所属仓库\nA-04,WH-OFF\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().get(0).getMessage().contains("已停用"));
    }

    @Test
    void location_blankWarehouseMeansUnassigned() {
        var r = locImport.importLocations(csv("编码,所属仓库\nA-05,\n"), false);
        assertTrue(r.isOk());
        ArgumentCaptor<Location> cap = ArgumentCaptor.forClass(Location.class);
        verify(locationService).create(cap.capture());
        assertNull(cap.getValue().getWarehouseId());
    }

    @Test
    void location_blankNameFallsBackToCode() {
        // 与 LocationService.validate 同口径:name 为空就退回编码(DDL 是 NOT NULL)
        var r = locImport.importLocations(csv("编码,名称\nA-06,\n"), false);
        assertTrue(r.isOk());
        ArgumentCaptor<Location> cap = ArgumentCaptor.forClass(Location.class);
        verify(locationService).create(cap.capture());
        assertEquals("A-06", cap.getValue().getName());
    }

    @Test
    void location_duplicateCodeRejected() {
        var r = locImport.importLocations(csv("编码\nA-01\nA-01\n"), true);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.getMessage().contains("文件中重复")));
    }

    @Test
    void location_errorsBlockWrite() {
        var r = locImport.importLocations(csv("编码,所属仓库\nA-07,不存在\n"), false);
        assertFalse(r.isOk());
        verify(locationService, never()).create(any());
    }

    // ==================== helpers ====================

    private static byte[] csv(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static Warehouse warehouse(int id, String code, String name, boolean enabled) {
        Warehouse w = new Warehouse();
        w.setId(id); w.setCode(code); w.setName(name); w.setEnabled(enabled);
        return w;
    }
}
