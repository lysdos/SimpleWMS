package com.wms.service;

import com.wms.dao.InventoryItemDao;
import com.wms.dao.StockCheckDao;
import com.wms.model.StockCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提交盘点**不改写明细**。
 *
 * 旧实现提交时把未盘行(counted_qty 为 NULL)补写成 0,于是「已盘数」被提交动作篡改:
 * 7 行里只盘了 4 行,提交后表头显示已盘 7 —— 观测事实被派生结论改掉了。
 * 现在提交只置状态 + 重算;未盘行按盘亏计入差异这件事,由
 * {@link StockCheckDao#updateStats(int)} 在 COMMITTED 状态下的 SQL 口径完成。
 *
 * ⚠️ 这个测试**钉不到 SQL 口径**(项目没有测试库,刻意的,见 OutboundServiceAddItemTest 的说明)。
 * 它能钉的是服务层不越权改明细;「提交后已盘数仍是真数过的那几行」只能靠端到端复验。
 */
@ExtendWith(MockitoExtension.class)
class StockCheckCommitTest {

    private static final int CHECK_ID = 42;

    @Mock private StockCheckDao dao;
    @Mock private InventoryItemDao itemDao;
    @Mock private JdbcTemplate jdbc;

    private StockCheckService service;

    @BeforeEach
    void setUp() {
        service = new StockCheckService(dao, itemDao, jdbc);
    }

    /** 带未盘行提交:一行都不许改,只重算一次汇总 */
    @Test
    void commit_neverRewritesUncountedLines() {
        when(dao.findCheckByIdForUpdate(CHECK_ID)).thenReturn(check(StockCheck.STATUS_DRAFT));
        when(dao.commit(CHECK_ID)).thenReturn(1);
        when(dao.updateStats(CHECK_ID)).thenReturn(1);
        when(dao.findCheckById(CHECK_ID)).thenReturn(check(StockCheck.STATUS_COMMITTED));
        when(dao.findItemsByCheckId(CHECK_ID)).thenReturn(List.of());

        service.commit(CHECK_ID);

        verify(dao, never()).updateCountedQty(anyInt(), anyInt());
        verify(dao, times(1)).updateStats(CHECK_ID);
    }

    /** 只有 DRAFT 能提交;已提交/已取消再点一次要给冲突,而不是重复重算 */
    @Test
    void commit_rejectsNonDraft() {
        when(dao.findCheckByIdForUpdate(CHECK_ID)).thenReturn(check(StockCheck.STATUS_COMMITTED));

        assertThrows(IllegalStateException.class, () -> service.commit(CHECK_ID));

        verify(dao, never()).commit(anyInt());
        verify(dao, never()).updateStats(anyInt());
    }

    /** 并发下状态已被别人改掉:UPDATE 匹配 0 行必须报错,不能当成功返回 */
    @Test
    void commit_zeroRowsThrows() {
        when(dao.findCheckByIdForUpdate(CHECK_ID)).thenReturn(check(StockCheck.STATUS_DRAFT));
        when(dao.commit(CHECK_ID)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.commit(CHECK_ID));

        verify(dao, never()).updateStats(anyInt());
    }

    private static StockCheck check(String status) {
        StockCheck c = new StockCheck();
        c.setId(CHECK_ID);
        c.setStatus(status);
        return c;
    }
}
