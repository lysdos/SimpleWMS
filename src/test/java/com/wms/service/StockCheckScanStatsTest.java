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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 盘点单扫码成功后**汇总重算的调用形状与顺序**。
 *
 * 现场出过两次问题,都在这条路上:
 *   1) 「6 行实盘都是 1,表头却停在已盘 5」—— 汇总由应用层"SELECT 明细 → 算 → 写回字面量",
 *      并发扫码时后提交的事务用旧快照覆盖了新值(丢失更新)。修法:口径整段挪进
 *      {@link StockCheckDao#updateStats(int)} 的一条原子 SQL。
 *   2) 改完立刻炸出「扫码失败」—— 那条 SQL 在 DML 里带相关子查询,属加锁读,会对该单整段明细
 *      加范围锁;两个并发事务各持自己的明细锁再互锁对方 → 死锁(真并发实测:改明细路径每轮
 *      2~4 个事务死锁,盘盈插入路径 8~9 个且插入行被回滚)。修法:事务开头先锁单头行。
 *      **所以正确性不只看 SQL 的形态,还看锁的位置** —— 本测试的顺序断言就是钉这个。
 *
 * ⚠️ 这个测试**测不到什么**,别误以为它守住了并发:项目没有测试库(刻意的,见
 * OutboundServiceAddItemTest 的说明),死锁与 SQL 口径的等价性在这里复现不了。它能钉的是:
 *   1) 先锁单头 → 再碰明细 → 最后重算,这个**顺序**(顺序错了就是死锁);
 *   2) 扫码成功**恰好触发一次**重算;
 *   3) 重复扫已盘的码走 422 且**不**触发重算(既有语义,避免物理扫描把人工登记的盘亏改成 1;
 *      也正是错数"不会自愈"的原因,别顺手改)。
 * 并发下的行为只能用真并发复验,做法与实测数据见 {@link StockCheckDao#findCheckByIdForUpdate(int)}。
 */
@ExtendWith(MockitoExtension.class)
class StockCheckScanStatsTest {

    private static final int CHECK_ID = 42;
    private static final int ITEM_ID = 7;
    private static final String CODE = "000000003500302029039358";

    @Mock private StockCheckDao dao;
    @Mock private InventoryItemDao itemDao;
    @Mock private JdbcTemplate jdbc;

    private StockCheckService service;

    @BeforeEach
    void setUp() {
        service = new StockCheckService(dao, itemDao, jdbc);
    }

    /** 快照里已有这行、且还没盘过 → 记实盘 1,然后重算一次 */
    @Test
    void scan_snapshotLine_recomputesStatsOnce() {
        stubDraftCheck();
        StockCheckItem line = line(11, ITEM_ID, 1, null);
        when(dao.findItemsByCheckId(CHECK_ID)).thenReturn(new ArrayList<>(List.of(line)));
        when(itemDao.findByCode(CODE)).thenReturn(item(ITEM_ID));
        when(dao.updateCountedQty(11, 1)).thenReturn(1);
        when(dao.updateStats(CHECK_ID)).thenReturn(1);

        service.scanCodeToCheck(CHECK_ID, CODE);

        // 顺序即修复本身:先锁单头 → 再碰明细 → 最后重算。锁要是落在碰明细之后,
        // 两个并发事务照样各持一把明细锁互等(死锁),这正是上一版的错法。
        InOrder order = inOrder(dao);
        order.verify(dao).findCheckByIdForUpdate(CHECK_ID);
        order.verify(dao).updateCountedQty(11, 1);
        order.verify(dao).updateStats(CHECK_ID);

        verify(dao, times(1)).updateStats(CHECK_ID);
    }

    /** 单外码(不在快照里)→ 追加盘盈行,同样只重算一次 */
    @Test
    void scan_surplusCode_appendsSurplusLineAndRecomputesOnce() {
        stubDraftCheck();
        when(dao.findItemsByCheckId(CHECK_ID)).thenReturn(new ArrayList<>());
        when(itemDao.findByCode(CODE)).thenReturn(item(ITEM_ID));
        when(dao.updateStats(CHECK_ID)).thenReturn(1);

        service.scanCodeToCheck(CHECK_ID, CODE);

        verify(dao).insertItem(any(StockCheckItem.class));
        verify(dao, times(1)).updateStats(CHECK_ID);
    }

    /** 已盘过的码 → 422,且在抛之前就返回,汇总一次都不重算 */
    @Test
    void scan_alreadyCounted_throws422_andNeverRecomputes() {
        stubDraftCheck();
        StockCheckItem counted = line(11, ITEM_ID, 1, 1);
        when(dao.findItemsByCheckId(CHECK_ID)).thenReturn(new ArrayList<>(List.of(counted)));
        when(itemDao.findByCode(CODE)).thenReturn(item(ITEM_ID));

        assertThrows(UnprocessableException.class, () -> service.scanCodeToCheck(CHECK_ID, CODE));

        verify(dao, never()).updateCountedQty(anyInt(), anyInt());
        verify(dao, never()).updateStats(anyInt());
    }

    // ---- helpers ----

    private void stubDraftCheck() {
        StockCheck c = new StockCheck();
        c.setId(CHECK_ID);
        c.setStatus(StockCheck.STATUS_DRAFT);
        // 写路径必须走**加锁读**:不加锁时两个并发扫码事务会各持自己的明细锁再互锁对方 → 死锁
        // (真并发实测:改明细路径每轮 2~4 个事务死锁、盘盈插入路径 8~9 个且插入行被回滚)。
        when(dao.findCheckByIdForUpdate(CHECK_ID)).thenReturn(c);
    }

    private static InventoryItem item(int id) {
        InventoryItem it = new InventoryItem();
        it.setId(id);
        it.setCode(CODE);
        return it;
    }

    private static StockCheckItem line(int id, int inventoryItemId, int systemQty, Integer countedQty) {
        StockCheckItem it = new StockCheckItem();
        it.setId(id);
        it.setInventoryItemId(inventoryItemId);
        it.setSystemQty(systemQty);
        it.setCountedQty(countedQty);
        return it;
    }
}
