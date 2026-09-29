package com.wms.service;

import com.wms.controller.UnprocessableException;
import com.wms.dao.InventoryItemDao;
import com.wms.dao.StockCheckDao;
import com.wms.model.InventoryItem;
import com.wms.model.StockCheck;
import com.wms.model.StockCheckItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 按仓库盘点时,扫到**别的仓库**的码怎么办(2026-09-29)。
 *
 * 原先一律 422 挡住。问题:现场真会把货串仓 —— 某仓的货落在本仓,盘点时扫到它,
 * 这次发现就没处留痕(而它在自己仓库那张单上会被算成盘亏,两边都对不上)。
 * 现在按单元状态分两种:
 *   - **在库(IN_STOCK)** → 放行,记盘盈行,并在行备注里写明来源库位。
 *     为什么不怕"同一实物被两张单各记一次盘盈":在库单元在**它自己仓库**的快照里,
 *     那边扫到会算「已盘」,不会也追加盘盈。
 *   - **非在库**(已出/已报废/已调拨/已锁定/待入库) → 继续 422。这类单元不在**任何**
 *     盘点的快照里,放行就真的会两边各记一次。
 *
 * 项目没有测试库(刻意的,见 StockCheckScanStatsTest 的说明),所以这里用 mock 钉**分支与备注文案**,
 * SQL 层面的等价性靠真栈验证。
 */
@ExtendWith(MockitoExtension.class)
class StockCheckScanWarehouseScopeTest {

    private static final int CHECK_ID = 42;
    private static final int ITEM_ID = 7;
    private static final int WH_A = 1;   // 盘点单所属仓库
    private static final int WH_B = 2;   // 单元记录的仓库(别的仓)
    private static final String CODE = "000000003500302029039358";
    private static final String LOC_B = "B-02-02";

    @Mock private StockCheckDao dao;
    @Mock private InventoryItemDao itemDao;
    @Mock private JdbcTemplate jdbc;

    private StockCheckService service;

    @BeforeEach
    void setUp() {
        service = new StockCheckService(dao, itemDao, jdbc);
    }

    // ==================== 跨仓:在库 → 记盘盈 + 来源备注 ====================

    @Test
    void crossWarehouse_inStock_isRecordedAsSurplusWithOriginRemark() {
        givenWarehouseCheck();
        givenUnitLocationInWarehouse(WH_B);
        StockCheckItem line = scanOutOfSnapshotCode(InventoryItem.STATUS_IN_STOCK);

        assertEquals(0, line.getSystemQty());
        assertEquals(1, line.getCountedQty());
        assertTrue(line.getRowRemark().contains("来自其他仓库"),
                "备注应写明来源,实际: " + line.getRowRemark());
        assertTrue(line.getRowRemark().contains(LOC_B),
                "备注应带上记录库位,实际: " + line.getRowRemark());
    }

    /** 库位没挂到任何仓库(warehouse_id 可空)→ 同样放行,但文案要分清是哪一种。 */
    @Test
    void locationNotAssignedToAnyWarehouse_inStock_labelsItDifferently() {
        givenWarehouseCheck();
        givenUnitLocationInWarehouse(null);
        StockCheckItem line = scanOutOfSnapshotCode(InventoryItem.STATUS_IN_STOCK);

        assertTrue(line.getRowRemark().contains("未挂到任何仓库"),
                "备注应说明库位未挂仓库,实际: " + line.getRowRemark());
    }

    // ==================== 跨仓:非在库 → 仍 422 ====================

    @Test
    void crossWarehouse_notInStock_isStillRejected422() {
        givenWarehouseCheck();
        givenUnitLocationInWarehouse(WH_B);
        when(dao.findItemsByCheckId(CHECK_ID)).thenReturn(new ArrayList<>());
        when(itemDao.findByCode(CODE)).thenReturn(item(InventoryItem.STATUS_OUT));

        assertThrows(UnprocessableException.class, () -> service.scanCodeToCheck(CHECK_ID, CODE));

        verify(dao, never()).insertItem(any(StockCheckItem.class));
    }

    // ==================== 同仓:不写备注,行为不变 ====================

    @Test
    void sameWarehouse_surplus_getsNoOriginRemark() {
        givenWarehouseCheck();
        givenUnitLocationInWarehouse(WH_A);
        StockCheckItem line = scanOutOfSnapshotCode(InventoryItem.STATUS_IN_STOCK);

        assertNull(line.getRowRemark(), "同仓的盘盈不该被标成跨仓来源");
    }

    /** 非按仓库的盘点单(ALL / SKU / LOCATION)根本不查库位归属。 */
    @Test
    void nonWarehouseScope_neverLooksUpLocation() {
        StockCheck c = new StockCheck();
        c.setId(CHECK_ID);
        c.setStatus(StockCheck.STATUS_DRAFT);
        c.setWarehouseId(null);
        when(dao.findCheckByIdForUpdate(CHECK_ID)).thenReturn(c);

        StockCheckItem line = scanOutOfSnapshotCode(InventoryItem.STATUS_OUT);

        assertNull(line.getRowRemark());
        verifyNoInteractions(jdbc);
    }

    // ---- helpers ----

    private void givenWarehouseCheck() {
        StockCheck c = new StockCheck();
        c.setId(CHECK_ID);
        c.setStatus(StockCheck.STATUS_DRAFT);
        c.setWarehouseId(WH_A);
        when(dao.findCheckByIdForUpdate(CHECK_ID)).thenReturn(c);
    }

    /** 该单元所在库位归属 warehouseId;传 null = 库位未挂仓库。 */
    private void givenUnitLocationInWarehouse(Integer warehouseId) {
        when(jdbc.queryForList(anyString(), eq(Integer.class), any()))
                .thenReturn(warehouseId == null
                        ? Collections.singletonList(null)
                        : List.of(warehouseId));
    }

    /** 扫一个不在快照里的码,返回被追加的盘盈行。 */
    private StockCheckItem scanOutOfSnapshotCode(String status) {
        when(dao.findItemsByCheckId(CHECK_ID)).thenReturn(new ArrayList<>());
        when(itemDao.findByCode(CODE)).thenReturn(item(status));
        when(dao.updateStats(CHECK_ID)).thenReturn(1);

        service.scanCodeToCheck(CHECK_ID, CODE);

        ArgumentCaptor<StockCheckItem> captor = ArgumentCaptor.forClass(StockCheckItem.class);
        verify(dao).insertItem(captor.capture());
        return captor.getValue();
    }

    private static InventoryItem item(String status) {
        InventoryItem it = new InventoryItem();
        it.setId(ITEM_ID);
        it.setCode(CODE);
        it.setLocationCode(LOC_B);
        it.setStatus(status);
        return it;
    }
}
