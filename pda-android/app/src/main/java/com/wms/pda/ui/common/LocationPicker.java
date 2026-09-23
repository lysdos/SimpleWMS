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
 *
 * ⚠️ 分组键用的是**仓库 id**,不是仓库名。仓库名没有唯一约束(只有 code 唯一),
 *    同名仓库按名分组会互相覆盖:被覆盖那个仓库的库位在所有 PDA 选择器里都选不到,
 *    getSelectedWarehouseId() 还会返回另一个仓库的 id(按仓库盘点会盘错仓)。
 */
public final class LocationPicker {

    /** 未分配组的键。不是真实仓库 id,只用于内部分组 */
    private static final String UNASSIGNED_KEY = "__unassigned__";

    private final Spinner spWarehouse;
    private final Spinner spLocation;
    /** 分组键 → 该组库位。仓库按 warehouseId 升序放入,未分配组固定最后 */
    private final Map<String, List<Location>> groups = new LinkedHashMap<>();
    /** 分组键 → 显示名(仓库名 /「(未分配)」)。显示与取值分开,键才能用不可读但唯一的 id */
    private final Map<String, String> labelOfKey = new LinkedHashMap<>();
    /** 分组键 → 仓库 id(「(未分配)」组记为 null)。按仓库盘点要把 warehouseId 发给后端 */
    private final Map<String, Integer> idOfGroup = new LinkedHashMap<>();
    /** 与 spLocation 的条目一一对应,用于按 position 取回真实库位(避免从显示文案反解) */
    private List<Location> current = new ArrayList<>();
    /** 仓库切换后的回调。Spinner 只允许一个监听器,所以依赖"当前仓库"的 UI 走这个口子刷新。 */
    private Runnable onGroupChanged;
    /** 库位下拉最前面是否加一项「不指定」(选中时 getSelectedCode() 返回 null) */
    private final boolean allowNone;
    /** 上面那一项的文案:新增时说「不指定(用商品默认库位)」,编辑时说「保持原库位」 */
    private final int noneLabelRes;
    /**
     * 期望选中的库位码。fillLocations 每次重填都会尽量把它恢复 ——
     * 仓库下拉的 onItemSelected 是**首次布局之后**才回调的(会再跑一次 fillLocations),
     * 不记住这个意图的话,select() 刚设好的选中项会被冲回第 0 项。
     */
    private String pendingCode;

    /**
     * @param preferredLocationCode 期望默认选中的库位码(如「该单元当前所在库位」);
     *                              它所属的仓库会被默认选中,查不到则落到第一组
     */
    public LocationPicker(Spinner spWarehouse, Spinner spLocation,
                          List<Location> locations, String preferredLocationCode) {
        this(spWarehouse, spLocation, locations, preferredLocationCode, null, false,
                R.string.location_picker_none);
    }

    /**
     * @param allowNone 库位下拉最前面加一项「不指定」。入库明细用 —— 空值表示
     *                  「用商品的默认库位」,与 Web 端那个空选项同义。
     */
    public LocationPicker(Spinner spWarehouse, Spinner spLocation,
                          List<Location> locations, String preferredLocationCode, boolean allowNone) {
        this(spWarehouse, spLocation, locations, preferredLocationCode, null, allowNone,
                R.string.location_picker_none);
    }

    /**
     * @param preferredWarehouseId  期望默认选中的仓库 id。用于「当前库位已被排除出候选」
     *                              的场景(如调拨不能调到自己):这时没法用库位码定位,
     *                              但可以按仓库定位,免得静默落到第一个仓库。
     * @param noneLabelRes          「不指定」那一项的文案资源
     */
    public LocationPicker(Spinner spWarehouse, Spinner spLocation,
                          List<Location> locations, String preferredLocationCode,
                          Integer preferredWarehouseId, boolean allowNone, int noneLabelRes) {
        this.allowNone = allowNone;
        this.noneLabelRes = noneLabelRes;
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
            String key = "W" + e.getKey();
            groups.put(key, e.getValue());
            idOfGroup.put(key, e.getKey());
            labelOfKey.put(key, nameOfId.get(e.getKey()));
        }
        if (!unassigned.isEmpty()) {
            groups.put(UNASSIGNED_KEY, unassigned);
            idOfGroup.put(UNASSIGNED_KEY, null);   // 未分配组没有仓库 id
            labelOfKey.put(UNASSIGNED_KEY, unassignedLabel);
        }

        final List<String> keys = new ArrayList<>(groups.keySet());
        List<String> labels = new ArrayList<>(keys.size());
        for (String k : keys) labels.add(labelOfKey.get(k));
        ArrayAdapter<String> whAdapter = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, labels);
        whAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spWarehouse.setAdapter(whAdapter);

        pendingCode = preferredLocationCode;
        int defIdx = groupIndexOf(keys, preferredLocationCode);
        if (defIdx < 0 && preferredWarehouseId != null) {
            defIdx = indexOfWarehouse(keys, preferredWarehouseId);
        }
        if (defIdx < 0) defIdx = 0;
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
     * 默认选中哪一组 = 指定库位码所在的组。查不到返回 -1(由调用方决定退路)。
     *
     * 注意:不能拿「仓库名」比对 —— 未分配组的仓库名是 null,与「没找到」无法区分,
     * 那样未分配组就永远不能被默认选中。
     */
    private int groupIndexOf(List<String> keys, String preferredLocationCode) {
        if (preferredLocationCode == null) return -1;
        for (int i = 0; i < keys.size(); i++) {
            for (Location l : groups.get(keys.get(i))) {
                if (preferredLocationCode.equalsIgnoreCase(l.code)) return i;
            }
        }
        return -1;
    }

    private int indexOfWarehouse(List<String> keys, Integer warehouseId) {
        for (int i = 0; i < keys.size(); i++) {
            if (warehouseId.equals(idOfGroup.get(keys.get(i)))) return i;
        }
        return -1;
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
            if (l == null) { labels.add(ctx.getString(noneLabelRes)); continue; }
            // 展示 code,有别名时补在括号里;真实值仍从 current 按位置取
            labels.add((l.name != null && !l.name.isEmpty() && !l.name.equals(l.code))
                    ? l.code + " (" + l.name + ")" : l.code);
        }
        ArrayAdapter<String> ad = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spLocation.setAdapter(ad);

        // 选中项:优先恢复 select() 指定的库位码,否则落到第 0 项。
        // 必须显式选:换 adapter 后框架未必把选中位置落到 0,不设的话 getSelectedCode()
        // 可能返回 null(调拨会把 toLocation=null 发出去)。
        int idx = 0;
        if (pendingCode != null) {
            for (int i = 0; i < current.size(); i++) {
                Location l = current.get(i);
                if (l != null && pendingCode.equalsIgnoreCase(l.code)) { idx = i; break; }
            }
        }
        if (!current.isEmpty()) spLocation.setSelection(idx);
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
     *
     * 选中意图记在 pendingCode 上而不是只调一次 setSelection:仓库下拉的回调
     * 会在首次布局后再跑一遍 fillLocations,只设一次会被它冲回第 0 项。
     */
    public void select(String code) {
        if (code == null || code.isEmpty() || spLocation == null) return;
        pendingCode = code;
        List<String> keys = new ArrayList<>(groups.keySet());
        for (int gi = 0; gi < keys.size(); gi++) {
            List<Location> locs = groups.get(keys.get(gi));
            for (int i = 0; i < locs.size(); i++) {
                if (code.equalsIgnoreCase(locs.get(i).code)) {
                    spWarehouse.setSelection(gi);
                    fillLocations(keys.get(gi));
                    return;
                }
            }
        }
    }

    /**
     * 默认选中某个仓库(按 id)。用于"当前库位已从候选里排除"的场景(调拨不能调到自己):
     * 这时拿不到可用的库位码,但可以按仓库定位,免得静默落到第一个仓库造成跨仓误操作。
     */
    public void selectWarehouse(Integer warehouseId) {
        if (warehouseId == null) return;
        List<String> keys = new ArrayList<>(groups.keySet());
        int i = indexOfWarehouse(keys, warehouseId);
        if (i < 0) return;
        spWarehouse.setSelection(i);
        fillLocations(keys.get(i));
    }
}
