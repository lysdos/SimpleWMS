package com.wms.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分页结果的页数计算与页码钳位。
 *
 * 这两个数是前端分页器直接显示的东西("第 3 / 2 页"这种就是钳位没做对),
 * 单独钉住比在 service 测试里间接覆盖更直接。
 */
class PageResultTest {

    @Test
    void pages_roundsUp() {
        assertEquals(1, new PageResult<>(List.of(), 0, 1, 20).getPages(), "空结果也算一页");
        assertEquals(1, new PageResult<>(List.of(), 20, 1, 20).getPages());
        assertEquals(2, new PageResult<>(List.of(), 21, 1, 20).getPages());
        assertEquals(5, new PageResult<>(List.of(), 100, 1, 20).getPages());
    }

    @Test
    void pages_zeroPageSize_fallsBackToOne() {
        assertEquals(1, new PageResult<>(List.of(), 100, 1, 0).getPages());
    }

    @Test
    void clampPage_zeroOrNegative_becomesFirstPage() {
        assertEquals(1, PageResult.clampPage(0, 20, 100));
        assertEquals(1, PageResult.clampPage(-5, 20, 100));
    }

    @Test
    void clampPage_beyondLast_clampsToLast() {
        // 别人删掉几行后你还停在第 99 页 —— 钳到最后,而不是给一个空页
        assertEquals(2, PageResult.clampPage(99, 20, 21));
        assertEquals(5, PageResult.clampPage(99, 20, 100));
    }

    @Test
    void clampPage_withinRange_unchanged() {
        assertEquals(3, PageResult.clampPage(3, 20, 100));
    }

    @Test
    void clampPage_emptyTotal_alwaysFirstPage() {
        assertEquals(1, PageResult.clampPage(7, 20, 0));
    }
}
