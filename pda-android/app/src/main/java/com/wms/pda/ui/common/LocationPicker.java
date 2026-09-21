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

    private final Spinner spWarehouse;
    private final Spinner spLocation;
    /** 组名 → 该组库位。仓库按 warehouseId 升序放入,未分配组固定最后 */
    private final Map<String, List<Location>> groups = new LinkedHashMap<>();
    /** 组名 → 仓库 id(「(未分配)」组记为 null)。按仓库盘点要把 warehouseId 发给后端 */
    private final Map<String, Integer> idOfGroup = new LinkedHashMap<>();
    /** 与 spLocation 的条目一一对应,用于按 position 取回真实库位(避免从显示文案反解) */
    private List<Location> current = new ArrayList<>();
    /** 仓库切换后的回调。Spinner 只允许一个监听器,所以依赖"当前仓库"的 UI 走这个口子刷新。 */
    private Runnable onGroupChanged;
    /** 库位下拉最前面是否加一项「不指定」(选中时 getSelectedCode() 返回 null) */
    private final boolean allowNone;

    /**
     * @param preferredLocationCode 期望默认选中的库位码(如「该单元当前所在库位」);
     *                              它所属的仓库会被默认选中,查不到则落到第一组
     */
    public LocationPicker(Spinner spWarehouse, Spinner spLocation,
                          List<Location> locations, String preferredLocationCode) {
        this(spWarehouse, spLocation, locations, preferredLocationCode, false);
    }

    /**
     * @param allowNone 库位下拉最前面加一项「不指定」。入库明细用 —— 空值表示
     *                  「用商品的默认库位」,与 Web 端那个空选项同义。
     */
    public LocationPicker(Spinner spWarehouse, Spinner spLocation,
                          List<Location> locations, String preferredLocationCode, boolean allowNone) {
        this.allowNone = allowNone;
        this.spWarehouse = spWarehouse;
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
            String name = nameOfId.get(e.getKey());
            groups.put(name, e.getValue());
            idOfGroup.put(name, e.getKey());
        }
        if (!unassigned.isEmpty()) {
            groups.put(unassignedLabel, unassigned);
            idOfGroup.put(unassignedLabel, null);   // 未分配组没有仓库 id
        }

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
                if (onGroupChanged != null) onGroupChanged.run();
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
        Context ctx = spWarehouse.getContext();
        current = (groupKey == null || !groups.containsKey(groupKey))
                ? new ArrayList<>() : new ArrayList<>(groups.get(groupKey));
        // 「不指定」放最前,item 为 null —— getSelectedLocation() 会返回 null,
        // 调用方据此发空库位(后端回落到商品默认库位)
        if (allowNone) current.add(0, null);

        // 第二级下拉可以为 null:「按仓库」盘点只要仓库 + 该仓的库位清单
        // (用 currentGroupLocations() 自己弹多选),不需要单个库位下拉。
        if (spLocation == null) return;

        List<String> labels = new ArrayList<>(current.size());
        for (Location l : current) {
            if (l == null) { labels.add(ctx.getString(R.string.location_picker_none)); continue; }
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

    /** 当前选中的库位;没有可选库位、或选中「不指定」时返回 null */
    public Location getSelectedLocation() {
        if (spLocation == null) return null;
        int pos = spLocation.getSelectedItemPosition();
        if (pos < 0 || pos >= current.size()) return null;
        return current.get(pos);
    }

    /** 当前选中的库位码(发给后端的值);没有可选库位时返回 null */
    public String getSelectedCode() {
        Location l = getSelectedLocation();
        return l == null ? null : l.code;
    }

    /** 当前选中仓库的 id;「(未分配)」组或没有分组时返回 null */
    public Integer getSelectedWarehouseId() {
        List<String> keys = new ArrayList<>(groups.keySet());
        int pos = spWarehouse.getSelectedItemPosition();
        if (pos < 0 || pos >= keys.size()) return null;
        return idOfGroup.get(keys.get(pos));
    }

    /**
     * 当前选中仓库下的全部库位(副本)。
     * 供"按仓库盘点"的**多选库位**用 —— 那里要的不是单个选中值,而是整组。
     */
    public List<Location> currentGroupLocations() {
        List<Location> out = new ArrayList<>(current.size());
        for (Location l : current) if (l != null) out.add(l);   // 剔除「不指定」占位
        return out;
    }

    /** 仓库切换后的回调(用于刷新依赖"当前仓库"的 UI,如多选库位的计数) */
    public void setOnGroupChangedListener(Runnable r) {
        this.onGroupChanged = r;
    }

    /**
     * 选中指定库位码(会自动切到它所属的仓库)。
     * 编辑既有明细时用:把这一行原来的库位显示出来,免得"只改数量"顺手把库位改没了。
     * 找不到(库位已删)就不动,保持当前选择 —— 不猜。
     */
    public void select(String code) {
        if (code == null || code.isEmpty() || spLocation == null) return;
        List<String> keys = new ArrayList<>(groups.keySet());
        for (int gi = 0; gi < keys.size(); gi++) {
            List<Location> locs = groups.get(keys.get(gi));
            for (int i = 0; i < locs.size(); i++) {
                if (code.equalsIgnoreCase(locs.get(i).code)) {
                    spWarehouse.setSelection(gi);
                    fillLocations(keys.get(gi));       // setSelection 的回调未必同步,再填一次
                    spLocation.setSelection(allowNone ? i + 1 : i);
                    return;
                }
            }
        }
    }
}
