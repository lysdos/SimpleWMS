package com.wms.pda.ui.common;

import android.app.Activity;
import android.widget.Spinner;

import com.wms.pda.data.model.Location;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * 两级库位选择的纯逻辑验证(仓库分组 / 默认组 / 取值)。
 *
 * 重点覆盖三个容易做错的点:
 *  1) **未分配组必须能选、也必须能被默认选中** —— 未分配库位的 warehouseName 是 null,
 *     若拿仓库名去比对默认值,null 与「没找到」无法区分,未分配组就永远选不中。
 *  2) 仓库顺序按 warehouseId(与 Web 端一致),**不是**按名称 —— 中文名的码点序是乱序。
 *  3) getSelectedCode() 必须稳定返回裸 code(换 adapter 后不显式 setSelection 会拿到 null)。
 */
@RunWith(RobolectricTestRunner.class)
// 28:本机已缓存 API 28 的 android-all 运行时,离线也能跑(34 需要 Robolectric ≥4.11)
// qualifiers:断言的是中文文案(「(未分配)」),必须钉住 locale —— Robolectric 默认 locale 是英文,
// 加了 values-en/ 之后会去取英文资源,这几条断言就会莫名变红。本测试验的是**分组与选中逻辑**,
// 不该随界面语言漂移,所以钉死 zh-rCN(与 SettingsActivity.LANG_ZH 同值)。
@Config(sdk = 28, qualifiers = "zh-rCN")
public class LocationPickerTest {

    private Spinner spWarehouse;
    private Spinner spLocation;

    @Before
    public void setUp() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        spWarehouse = new Spinner(a);
        spLocation = new Spinner(a);
    }

    private static Location loc(String code, Integer whId, String whName) {
        Location l = new Location();
        l.code = code;
        l.name = code;
        l.warehouseId = whId;
        l.warehouseName = whName;
        l.enabled = true;
        return l;
    }

    private List<String> warehouseLabels() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < spWarehouse.getAdapter().getCount(); i++) {
            out.add(String.valueOf(spWarehouse.getAdapter().getItem(i)));
        }
        return out;
    }

    /** 模拟「用户切了仓库下拉」:setSelection 只改位置,真机要等一次布局才回调,测试里直接驱动回调 */
    private void switchWarehouse(int position) {
        spWarehouse.setSelection(position);
        spWarehouse.getOnItemSelectedListener().onItemSelected(spWarehouse, spWarehouse, position, 0);
    }

    @Test
    public void groups_byWarehouseId_orderAndUnassignedLast() {
        new LocationPicker(spWarehouse, spLocation, Arrays.asList(
                loc("C-01", null, null),
                loc("B-01", 2, "乙仓"),
                loc("A-01", 1, "甲仓")), null);

        // 按 warehouseId 升序(1 → 2),未分配最后
        assertEquals(Arrays.asList("甲仓", "乙仓", "(未分配)"), warehouseLabels());
        assertEquals(0, spWarehouse.getSelectedItemPosition());
    }

    @Test
    public void changingWarehouse_swapsLocationList() {
        LocationPicker p = new LocationPicker(spWarehouse, spLocation, Arrays.asList(
                loc("A-01", 1, "甲仓"),
                loc("A-02", 1, "甲仓"),
                loc("B-01", 2, "乙仓")), "A-01");

        assertEquals("甲仓", spWarehouse.getSelectedItem());
        assertEquals(2, spLocation.getAdapter().getCount());
        assertEquals("A-01", p.getSelectedCode());

        switchWarehouse(1);   // 切到「乙仓」
        assertEquals(1, spLocation.getAdapter().getCount());
        assertEquals("B-01", spLocation.getAdapter().getItem(0));
        assertEquals("B-01", p.getSelectedCode());
    }

    @Test
    public void preferredCode_selectsItsWarehouse() {
        LocationPicker p = new LocationPicker(spWarehouse, spLocation, Arrays.asList(
                loc("A-01", 1, "甲仓"),
                loc("B-01", 2, "乙仓")), "B-01");

        assertEquals("乙仓", spWarehouse.getSelectedItem());
        assertEquals("B-01", p.getSelectedCode());
    }

    /** 关键:当前库位未分配仓库时,默认组必须是「(未分配)」而不是第一个仓库 */
    @Test
    public void preferredCode_inUnassignedGroup_selectsUnassignedGroup() {
        LocationPicker p = new LocationPicker(spWarehouse, spLocation, Arrays.asList(
                loc("A-01", 1, "甲仓"),
                loc("Z-99", null, null)), "Z-99");

        assertEquals("(未分配)", spWarehouse.getSelectedItem());
        assertEquals("Z-99", p.getSelectedCode());
    }

    /** 未分配的库位必须真的能选出来,不能被丢掉 */
    @Test
    public void unassignedLocation_isSelectable() {
        LocationPicker p = new LocationPicker(spWarehouse, spLocation,
                Collections.singletonList(loc("Z-99", null, null)), null);

        assertEquals(Collections.singletonList("(未分配)"), warehouseLabels());
        assertEquals(1, spLocation.getAdapter().getCount());
        assertNotNull(p.getSelectedLocation());
        assertEquals("Z-99", p.getSelectedCode());
    }

    @Test
    public void warehouseNameIsTrimmed_andBlankTreatedAsUnassigned() {
        new LocationPicker(spWarehouse, spLocation, Arrays.asList(
                loc("A-01", 1, "  甲仓  "),
                loc("B-01", 2, "   ")), null);

        assertEquals(Arrays.asList("甲仓", "(未分配)"), warehouseLabels());
    }

    @Test
    public void unknownPreferredCode_fallsBackToFirstGroup() {
        LocationPicker p = new LocationPicker(spWarehouse, spLocation, Arrays.asList(
                loc("A-01", 1, "甲仓"),
                loc("B-01", 2, "乙仓")), "GONE-CODE");

        assertEquals(0, spWarehouse.getSelectedItemPosition());
        assertEquals("A-01", p.getSelectedCode());
    }

    @Test
    public void emptyList_yieldsNullCode() {
        LocationPicker p = new LocationPicker(spWarehouse, spLocation,
                new ArrayList<Location>(), null);

        assertEquals(0, spWarehouse.getAdapter().getCount());
        assertNull(p.getSelectedLocation());
        assertNull(p.getSelectedCode());
    }

    /** 跳过 null / 空码的脏项,不让它们进下拉 */
    @Test
    public void skipsNullOrBlankCodeEntries() {
        LocationPicker p = new LocationPicker(spWarehouse, spLocation, Arrays.asList(
                null, loc("", 1, "甲仓"), loc("A-01", 1, "甲仓")), null);

        assertEquals("A-01", p.getSelectedCode());
        assertEquals(1, spLocation.getAdapter().getCount());
        assertEquals("A-01", spLocation.getAdapter().getItem(0));
    }
}
