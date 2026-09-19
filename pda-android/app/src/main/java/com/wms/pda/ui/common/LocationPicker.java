package com.wms.pda.ui.common;

import android.content.Context;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.wms.pda.R;
import com.wms.pda.data.model.Location;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 两级库位选择:仓库 → 库位。
 *
 * 为什么不是一级平铺:Android 的 Spinner **没有 optgroup**(Web 端那种分组下拉在这做不到)。
 * 多仓库后把全部库位平铺成一行,`上海一号仓/A-01-01` 这种前缀在小屏上很容易被截断或扫过去,
 * 而两个仓库各有一个 `A-01` 时人眼根本分不出来 —— 所以把「哪个仓库」变成一个必须先确认的步骤。
 *
 * 用法:
 * <pre>
 *   LocationPicker p = new LocationPicker(spWarehouse, spLocation, locs, 当前库位码);
 *   String code = p.getSelectedCode();   // 发给后端的永远是裸 code
 * </pre>
 *
 * ⚠️ 未分配仓库的库位归入「(未分配)」组。**这一组必须保留**,
 *    否则那些库位在 PDA 上就再也选不到了。
 */
public final class LocationPicker {

    private final Spinner spLocation;
    /** 组名 → 该组库位。仓库按 warehouseId 升序放入,未分配组固定最后 */
    private final Map<String, List<Location>> groups = new LinkedHashMap<>();
    /** 与 spLocation 的条目一一对应,用于按 position 取回真实库位(避免从显示文案反解) */
    private List<Location> current = new ArrayList<>();

    /**
     * @param preferredLocationCode 期望默认选中的库位码(如「该单元当前所在库位」);
     *                              它所属的仓库会被默认选中,查不到则落到第一组
     */
    public LocationPicker(Spinner spWarehouse, Spinner spLocation,
                          List<Location> locations, String preferredLocationCode) {
        this.spLocation = spLocation;
        Context ctx = spWarehouse.getContext();
        String unassignedLabel = ctx.getString(R.string.location_warehouse_unassigned);

        // 分组:按 warehouseId 升序 —— 与 Web 端仓库列表(order by id asc)同序。
        // 刻意**不按仓库名**排序:中文名的 Unicode 码点序对人眼等于乱序
        // (乙 U+4E59 会排在 甲 U+7532 前面)。未分配的归一组,固定排最后。
        Map<Integer, List<Location>> byId = new TreeMap<>();
        Map<Integer, String> nameOfId = new HashMap<>();
        List<Location> unassigned = new ArrayList<>();
        if (locations != null) {
            for (Location l : locations) {
                if (l == null || l.code == null || l.code.isEmpty()) continue;
                String wh = warehouseNameOf(l);
                if (wh == null || l.warehouseId == null) {
                    unassigned.add(l);
                } else {
                    List<Location> bucket = byId.get(l.warehouseId);
                    if (bucket == null) { bucket = new ArrayList<>(); byId.put(l.warehouseId, bucket); }
                    bucket.add(l);
                    nameOfId.put(l.warehouseId, wh);
                }
            }
        }
        for (Map.Entry<Integer, List<Location>> e : byId.entrySet()) {
            groups.put(nameOfId.get(e.getKey()), e.getValue());
        }
        if (!unassigned.isEmpty()) groups.put(unassignedLabel, unassigned);

        final List<String> keys = new ArrayList<>(groups.keySet());
        ArrayAdapter<String> whAdapter = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, keys);
        whAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spWarehouse.setAdapter(whAdapter);

        final int defIdx = groupIndexOf(keys, preferredLocationCode);
        if (!keys.isEmpty()) spWarehouse.setSelection(defIdx);

        // 监听必须在 setSelection 之后挂:否则初始化那次 setSelection 会走一遍回调
        spWarehouse.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                fillLocations(keys.isEmpty() ? null : keys.get(position));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        // Spinner 的 onItemSelected 要等首次布局才触发,这里先手动填一次,别留空窗期
        fillLocations(keys.isEmpty() ? null : keys.get(defIdx));
    }

    /**
     * 默认选中哪一组 = 指定库位码所在的组。查不到返回 0。
     *
     * 注意:不能拿「仓库名」比对 —— 未分配组的仓库名是 null,与「没找到」无法区分,
     * 那样未分配组就永远不能被默认选中。
     */
    private int groupIndexOf(List<String> keys, String preferredLocationCode) {
        if (preferredLocationCode == null) return 0;
        for (int i = 0; i < keys.size(); i++) {
            for (Location l : groups.get(keys.get(i))) {
                if (preferredLocationCode.equalsIgnoreCase(l.code)) return i;
            }
        }
        return 0;
    }

    private static String warehouseNameOf(Location l) {
        return (l.warehouseName == null || l.warehouseName.trim().isEmpty())
                ? null : l.warehouseName.trim();
    }

    private void fillLocations(String groupKey) {
        Context ctx = spLocation.getContext();
        current = (groupKey == null || !groups.containsKey(groupKey))
                ? new ArrayList<>() : groups.get(groupKey);

        List<String> labels = new ArrayList<>(current.size());
        for (Location l : current) {
            // 展示 code,有别名时补在括号里;真实值仍从 current 按位置取
            labels.add((l.name != null && !l.name.isEmpty() && !l.name.equals(l.code))
                    ? l.code + " (" + l.name + ")" : l.code);
        }
        ArrayAdapter<String> ad = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spLocation.setAdapter(ad);
        // 显式选中第一项:换 adapter 后框架未必把选中位置落到 0,
        // 不显式选的话 getSelectedCode() 可能返回 null(调拨会发 toLocation=null 出去)
        if (!current.isEmpty()) spLocation.setSelection(0);
    }

    /** 当前选中的库位;没有可选库位时返回 null */
    public Location getSelectedLocation() {
        int pos = spLocation.getSelectedItemPosition();
        if (pos < 0 || pos >= current.size()) return null;
        return current.get(pos);
    }

    /** 当前选中的库位码(发给后端的值);没有可选库位时返回 null */
    public String getSelectedCode() {
        Location l = getSelectedLocation();
        return l == null ? null : l.code;
    }
}
