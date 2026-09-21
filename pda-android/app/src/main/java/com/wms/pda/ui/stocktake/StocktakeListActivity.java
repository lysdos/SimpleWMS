package com.wms.pda.ui.stocktake;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.wms.pda.R;
import com.wms.pda.data.model.Enums;
import com.wms.pda.data.model.Location;
import com.wms.pda.data.model.StocktakeOrder;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;
import com.wms.pda.ui.common.LocationPicker;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 盘点单列表:待执行 / 已完成 两个 Tab。
 * "+" 按钮弹创建盘点单对话框(盘点方式 + 备注),创建后跳详情。
 */
public class StocktakeListActivity extends AppCompatActivity {

    private boolean onlyPending = true;
    private StocktakeAdapter adapter;
    private MaterialButton btnNew;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stocktake_list);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        TabLayout tabs = findViewById(R.id.tabs);
        RecyclerView list = findViewById(R.id.list);
        btnNew = findViewById(R.id.btn_new);
        btnNew.setEnabled(false);

        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new StocktakeAdapter(o -> {
            Intent i = new Intent(this, StocktakeDetailActivity.class);
            i.putExtra("orderId", o.id);
            if (o.dbId != null) i.putExtra("orderDbId", o.dbId);
            startActivity(i);
        });
        list.setAdapter(adapter);

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                onlyPending = tab.getPosition() == 0;
                reload();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        btnNew.setOnClickListener(v -> showCreateDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        RepositoryProvider.get().listStocktake(onlyPending, new WmsCallback<List<StocktakeOrder>>() {
            @Override public void onSuccess(List<StocktakeOrder> data) {
                adapter.submit(data);
                btnNew.setEnabled(true);
            }
            @Override public void onError(int code, String msg) {
                Toast.makeText(StocktakeListActivity.this, "加载失败: " + msg, Toast.LENGTH_SHORT).show();
                btnNew.setEnabled(true);
            }
        });
    }

    private void showCreateDialog() {
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_stocktake_new, null, false);
        RadioGroup rgScope = root.findViewById(R.id.rg_scope);
        RadioButton rbAll = root.findViewById(R.id.rb_scope_all);
        RadioButton rbSku = root.findViewById(R.id.rb_scope_sku);
        RadioButton rbLoc = root.findViewById(R.id.rb_scope_location);
        RadioButton rbWh = root.findViewById(R.id.rb_scope_warehouse);
        TextView tvKeywordLabel = root.findViewById(R.id.tv_keyword_label);
        EditText etKeyword = root.findViewById(R.id.et_keyword);
        Spinner spLocation = root.findViewById(R.id.sp_location);
        Spinner spWarehouse = root.findViewById(R.id.sp_warehouse);
        TextView tvWarehouseLabel = root.findViewById(R.id.tv_warehouse_label);
        TextView tvLocationLabel = root.findViewById(R.id.tv_location_label);
        TextView tvMultiLabel = root.findViewById(R.id.tv_multi_loc_label);
        TextView tvMultiValue = root.findViewById(R.id.tv_multi_loc_value);
        EditText etRemark = root.findViewById(R.id.et_remark);

        // 库位两级选择(仓库 → 库位),见 LocationPicker;数据到达后才能建,故用数组持有
        final LocationPicker[] picker = {null};

        // 按仓库模式的多选库位:按**仓库**分别记住勾选(切仓库不串),空集 = 整个仓库。
        // 键用仓库 id,「(未分配)」组是 null —— 那个组的库位没有仓库,按仓库盘点用不了。
        final Map<Integer, Set<String>> pickedByWh = new LinkedHashMap<>();
        final Runnable paintMultiLoc = () -> {
            Integer whId = picker[0] == null ? null : picker[0].getSelectedWarehouseId();
            Set<String> picked = pickedByWh.get(whId);
            int n = (picked == null) ? 0 : picked.size();
            tvMultiValue.setText(n == 0
                    ? getString(R.string.stocktake_multi_loc_all)
                    : getString(R.string.stocktake_multi_loc_fmt, n));
        };

        RepositoryProvider.get().listLocations(new WmsCallback<List<Location>>() {
            @Override public void onSuccess(List<Location> data) {
                picker[0] = new LocationPicker(spWarehouse, spLocation, data, null);
                picker[0].setOnGroupChangedListener(() -> paintMultiLoc.run());
            }
            @Override public void onError(int code, String msg) { /* 库位加载失败时留空,提交时校验提示 */ }
        });

        tvMultiValue.setOnClickListener(v -> {
            if (picker[0] == null) return;
            List<Location> locs = picker[0].currentGroupLocations();
            if (locs.isEmpty()) {
                Toast.makeText(this, R.string.stocktake_err_loc_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            Integer whId = picker[0].getSelectedWarehouseId();
            if (whId == null) {
                Toast.makeText(this, R.string.stocktake_err_warehouse_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            Set<String> picked = pickedByWh.get(whId);
            if (picked == null) { picked = new LinkedHashSet<>(); pickedByWh.put(whId, picked); }

            String[] codes = new String[locs.size()];
            String[] labels = new String[locs.size()];
            boolean[] checked = new boolean[locs.size()];
            for (int i = 0; i < locs.size(); i++) {
                Location l = locs.get(i);
                codes[i] = l.code;
                labels[i] = (l.name != null && !l.name.isEmpty() && !l.name.equals(l.code))
                        ? l.code + " (" + l.name + ")" : l.code;
                checked[i] = picked.contains(l.code);
            }
            final Set<String> target = picked;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.stocktake_multi_loc_title)
                    .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                        if (isChecked) target.add(codes[which]); else target.remove(codes[which]);
                    })
                    .setPositiveButton(R.string.action_confirm, (d, w) -> paintMultiLoc.run())
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
        });

        // 盘点方式联动:ALL 无输入 / SKU 文本框 / LOCATION 仓库+单库位 / WAREHOUSE 仓库+多选库位
        Runnable syncVisibility = () -> {
            boolean sku = rbSku.isChecked();
            boolean loc = rbLoc.isChecked();
            boolean wh  = rbWh.isChecked();
            tvKeywordLabel.setVisibility(sku ? View.VISIBLE : View.GONE);
            etKeyword.setVisibility(sku ? View.VISIBLE : View.GONE);
            // 两种模式都要先选仓库
            tvWarehouseLabel.setVisibility((loc || wh) ? View.VISIBLE : View.GONE);
            spWarehouse.setVisibility((loc || wh) ? View.VISIBLE : View.GONE);
            tvLocationLabel.setVisibility(loc ? View.VISIBLE : View.GONE);
            spLocation.setVisibility(loc ? View.VISIBLE : View.GONE);
            tvMultiLabel.setVisibility(wh ? View.VISIBLE : View.GONE);
            tvMultiValue.setVisibility(wh ? View.VISIBLE : View.GONE);
            if (wh) paintMultiLoc.run();
        };
        rgScope.setOnCheckedChangeListener((g, id) -> syncVisibility.run());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.stocktake_new_title)
                .setView(root)
                .setPositiveButton(R.string.action_submit, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            // 软键盘弹出时把内容顶上去(布局外层是 ScrollView),否则按 SKU 输入时看不见自己打的字
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String scope;
                String keyword = "";
                Integer warehouseId = null;
                if (rbSku.isChecked()) {
                    scope = "SKU";
                    keyword = etKeyword.getText().toString().trim();
                    if (keyword.isEmpty()) {
                        Toast.makeText(this, R.string.stocktake_err_sku_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                } else if (rbLoc.isChecked()) {
                    scope = "LOCATION";
                    // 发给后端的是裸库位码(两级下拉的选中值)
                    String sel = picker[0] == null ? null : picker[0].getSelectedCode();
                    keyword = sel == null ? "" : sel;
                    if (keyword.isEmpty()) {
                        Toast.makeText(this, R.string.stocktake_err_loc_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                } else if (rbWh.isChecked()) {
                    scope = "WAREHOUSE";
                    warehouseId = picker[0] == null ? null : picker[0].getSelectedWarehouseId();
                    if (warehouseId == null) {
                        Toast.makeText(this, R.string.stocktake_err_warehouse_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Set<String> picked = pickedByWh.get(warehouseId);
                    // 不勾库位 = 空关键字,后端按"整个仓库"处理
                    keyword = (picked == null || picked.isEmpty()) ? "" : String.join(",", picked);
                } else {
                    scope = "ALL";
                }
                String remark = etRemark.getText().toString().trim();
                RepositoryProvider.get().createStocktake(scope, keyword, warehouseId, remark,
                        new WmsCallback<StocktakeOrder>() {
                    @Override public void onSuccess(StocktakeOrder o) {
                        Toast.makeText(StocktakeListActivity.this, "已创建盘点单 " + o.id, Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        Intent i = new Intent(StocktakeListActivity.this, StocktakeDetailActivity.class);
                        i.putExtra("orderId", o.id);
                        startActivity(i);
                    }
                    @Override public void onError(int code, String msg) {
                        Toast.makeText(StocktakeListActivity.this, "创建失败: " + msg, Toast.LENGTH_SHORT).show();
                    }
                });
            });
        });
        dialog.show();
    }
}