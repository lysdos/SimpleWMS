package com.wms.pda.ui.stocktake;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
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
import com.wms.pda.data.model.StocktakeOrder;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;

import java.util.ArrayList;
import java.util.List;

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
        TextView tvKeywordLabel = root.findViewById(R.id.tv_keyword_label);
        EditText etKeyword = root.findViewById(R.id.et_keyword);
        Spinner spLocation = root.findViewById(R.id.sp_location);
        EditText etRemark = root.findViewById(R.id.et_remark);

        // 库位下拉(按库位盘点时选择,与 web 端一致)
        List<String> locations = new ArrayList<>();
        ArrayAdapter<String> locAd = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, locations);
        locAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spLocation.setAdapter(locAd);
        RepositoryProvider.get().listLocations(new WmsCallback<List<String>>() {
            @Override public void onSuccess(List<String> data) {
                locations.clear();
                locations.addAll(data);
                locAd.notifyDataSetChanged();
            }
            @Override public void onError(int code, String msg) { /* 库位加载失败时留空,提交时校验提示 */ }
        });

        // 盘点方式联动:ALL 隐藏输入,SKU 显示关键字输入框,LOCATION 显示库位下拉
        Runnable syncVisibility = () -> {
            boolean sku = rbSku.isChecked();
            boolean loc = rbLoc.isChecked();
            tvKeywordLabel.setVisibility(sku ? View.VISIBLE : View.GONE);
            etKeyword.setVisibility(sku ? View.VISIBLE : View.GONE);
            spLocation.setVisibility(loc ? View.VISIBLE : View.GONE);
        };
        rgScope.setOnCheckedChangeListener((g, id) -> syncVisibility.run());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.stocktake_new_title)
                .setView(root)
                .setPositiveButton(R.string.action_submit, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String scope;
                String keyword = "";
                if (rbSku.isChecked()) {
                    scope = "SKU";
                    keyword = etKeyword.getText().toString().trim();
                    if (keyword.isEmpty()) {
                        Toast.makeText(this, R.string.stocktake_err_sku_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                } else if (rbLoc.isChecked()) {
                    scope = "LOCATION";
                    Object sel = spLocation.getSelectedItem();
                    keyword = sel == null ? "" : sel.toString();
                    if (keyword.isEmpty()) {
                        Toast.makeText(this, R.string.stocktake_err_loc_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                } else {
                    scope = "ALL";
                }
                String remark = etRemark.getText().toString().trim();
                RepositoryProvider.get().createStocktake(scope, keyword, remark, new WmsCallback<StocktakeOrder>() {
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