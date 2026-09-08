package com.wms.pda.ui.stocktake;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
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
 * "+" 按钮弹创建盘点单对话框(仓库 + 方式),创建后跳详情。
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
        Spinner spWh = root.findViewById(R.id.sp_warehouse);
        Spinner spMode = root.findViewById(R.id.sp_mode);

        ArrayAdapter<String> whAd = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"主仓", "分仓"});
        whAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spWh.setAdapter(whAd);

        ArrayAdapter<String> mdAd = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{getString(R.string.stocktake_mode_all),
                        getString(R.string.stocktake_mode_movement),
                        getString(R.string.stocktake_mode_sample)});
        mdAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMode.setAdapter(mdAd);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.stocktake_new_title)
                .setView(root)
                .setPositiveButton(R.string.action_submit, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String wh = (String) spWh.getSelectedItem();
                String modeLabel = (String) spMode.getSelectedItem();
                StocktakeOrder.Mode mode;
                if (modeLabel.equals(getString(R.string.stocktake_mode_all)))       mode = StocktakeOrder.Mode.ALL;
                else if (modeLabel.equals(getString(R.string.stocktake_mode_movement))) mode = StocktakeOrder.Mode.LOCATION;
                else                                                                  mode = StocktakeOrder.Mode.SKU;
                RepositoryProvider.get().createStocktake(wh, mode, new WmsCallback<StocktakeOrder>() {
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