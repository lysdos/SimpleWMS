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
import com.wms.pda.data.model.Product;
import com.wms.pda.data.model.StocktakeOrder;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;
import com.wms.pda.ui.common.LocationPicker;

import java.util.ArrayList;
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
                Toast.makeText(StocktakeListActivity.this,
                        getString(R.string.msg_load_failed, msg), Toast.LENGTH_SHORT).show();
                btnNew.setEnabled(true);
            }
        });
    }

    /** 商品多选弹窗(与库位多选同一套:setMultiChoiceItems + 回写计数) */
    private void showSkuPicker(List<Product> products, Set<String> picked, Runnable onDone) {
        // 勾选是边点边写进 picked 的,所以「取消」必须还原,否则取消等于确认
        Set<String> before = new LinkedHashSet<>(picked);
        String[] labels = new String[products.size()];
        boolean[] checked = new boolean[products.size()];
        for (int i = 0; i < products.size(); i++) {
            Product p = products.get(i);
            labels[i] = (p.sku == null ? "?" : p.sku) + " · " + (p.name == null ? "" : p.name);
            checked[i] = p.sku != null && picked.contains(p.sku);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.stocktake_sku_multi_title)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                    String sku = products.get(which).sku;
                    if (sku == null) return;
                    if (isChecked) picked.add(sku); else picked.remove(sku);
                })
                .setPositiveButton(R.string.action_confirm, (d, w) -> onDone.run())
                .setNegativeButton(R.string.action_cancel, (d, w) -> {
                    picked.clear();
                    picked.addAll(before);
                })
                .show();
    }

    private void showCreateDialog() {
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_stocktake_new, null, false);
        RadioGroup rgScope = root.findViewById(R.id.rg_scope);
        RadioButton rbSku = root.findViewById(R.id.rb_scope_sku);
        RadioButton rbWh = root.findViewById(R.id.rb_scope_warehouse);
        TextView tvSkuLabel = root.findViewById(R.id.tv_sku_label);
        TextView tvSkuValue = root.findViewById(R.id.tv_sku_value);
        Spinner spWarehouse = root.findViewById(R.id.sp_warehouse);
        TextView tvWarehouseLabel = root.findViewById(R.id.tv_warehouse_label);
        TextView tvMultiLabel = root.findViewById(R.id.tv_multi_loc_label);
        TextView tvMultiValue = root.findViewById(R.id.tv_multi_loc_value);
        EditText etRemark = root.findViewById(R.id.et_remark);

        // 仓库分组(第二级库位下拉传 null:按仓库盘点只要仓库 + 该仓的库位清单,
        // 用下面的多选弹窗自己选)。数据到达后才能建,故用数组持有。
        final LocationPicker[] picker = {null};

        // 按 SKU 的**商品多选**:Android 的 Spinner 不支持多选,用 setMultiChoiceItems
        // (与库位多选同一套)。商品列表第一次点开才拉,不在进弹窗时就请求。
        final List<Product> products = new ArrayList<>();
        final Set<String> pickedSkus = new LinkedHashSet<>();
        final boolean[] skuLoaded = {false};
        final Runnable paintSkuValue = () -> tvSkuValue.setText(pickedSkus.isEmpty()
                ? getString(R.string.stocktake_sku_none)
                : getString(R.string.stocktake_sku_multi_fmt, pickedSkus.size()));
        tvSkuValue.setOnClickListener(v -> {
            if (!skuLoaded[0]) {
                RepositoryProvider.get().listProducts(new WmsCallback<List<Product>>() {
                    @Override public void onSuccess(List<Product> list) {
                        products.clear();
                        if (list != null) products.addAll(list);
                        skuLoaded[0] = true;
                        if (products.isEmpty()) {
                            Toast.makeText(StocktakeListActivity.this, R.string.msg_no_products, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showSkuPicker(products, pickedSkus, paintSkuValue);
                    }
                    @Override public void onError(int code, String msg) {
                        Toast.makeText(StocktakeListActivity.this,
                                getString(R.string.msg_load_products_failed, msg), Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }
            if (products.isEmpty()) {
                Toast.makeText(this, R.string.msg_no_products, Toast.LENGTH_SHORT).show();
                return;
            }
            showSkuPicker(products, pickedSkus, paintSkuValue);
        });

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
                picker[0] = new LocationPicker(spWarehouse, null, data, null);
                picker[0].setOnGroupChangedListener(() -> paintMultiLoc.run());
            }
            @Override public void onError(int code, String msg) { /* 库位加载失败时留空,提交时校验提示 */ }
        });

        tvMultiValue.setOnClickListener(v -> {
            if (picker[0] == null) return;
            List<Location> locs = picker[0].currentGroupLocations();
            if (locs.isEmpty()) {
                Toast.makeText(this, R.string.stocktake_err_wh_no_loc, Toast.LENGTH_SHORT).show();
                return;
            }
            Integer whId = picker[0].getSelectedWarehouseId();
            if (whId == null) {
                Toast.makeText(this, R.string.stocktake_err_warehouse_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            Set<String> picked = pickedByWh.get(whId);
            if (picked == null) { picked = new LinkedHashSet<>(); pickedByWh.put(whId, picked); }
            // 勾选是边点边写进 picked 的,所以「取消」必须还原,否则取消等于确认
            final Set<String> before = new LinkedHashSet<>(picked);

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
                    .setNegativeButton(R.string.action_cancel, (d, w) -> {
                        target.clear();
                        target.addAll(before);
                    })
                    .show();
        });

        // 盘点方式联动:ALL 无输入 / SKU 选商品 / WAREHOUSE 仓库 + 多选库位
        // (「按库位」已去掉 —— 按仓库只勾一个库位就等价于它)
        Runnable syncVisibility = () -> {
            boolean sku = rbSku.isChecked();
            boolean wh  = rbWh.isChecked();
            tvSkuLabel.setVisibility(sku ? View.VISIBLE : View.GONE);
            tvSkuValue.setVisibility(sku ? View.VISIBLE : View.GONE);
            tvWarehouseLabel.setVisibility(wh ? View.VISIBLE : View.GONE);
            spWarehouse.setVisibility(wh ? View.VISIBLE : View.GONE);
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
                    // 多选 -> 后端收的仍是逗号分隔串(接口与限额都没变)
                    keyword = String.join(",", pickedSkus);
                    if (keyword.isEmpty()) {
                        Toast.makeText(this, R.string.stocktake_err_sku_empty, Toast.LENGTH_SHORT).show();
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
                        Toast.makeText(StocktakeListActivity.this,
                                getString(R.string.stocktake_created_fmt, o.id), Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        Intent i = new Intent(StocktakeListActivity.this, StocktakeDetailActivity.class);
                        i.putExtra("orderId", o.id);
                        startActivity(i);
                    }
                    @Override public void onError(int code, String msg) {
                        Toast.makeText(StocktakeListActivity.this,
                                getString(R.string.msg_create_failed, msg), Toast.LENGTH_SHORT).show();
                    }
                });
            });
        });
        dialog.show();
    }
}