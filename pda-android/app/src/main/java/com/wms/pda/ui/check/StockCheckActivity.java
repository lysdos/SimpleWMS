package com.wms.pda.ui.check;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.wms.pda.R;
import com.wms.pda.data.SessionStore;
import com.wms.pda.model.ApiResponse;
import com.wms.pda.model.StockCheckDto;
import com.wms.pda.net.ApiClient;
import com.wms.pda.net.ApiException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 库存盘点:创建盘点单 → 逐条填实盘数 → 提交。 */
public class StockCheckActivity extends AppCompatActivity {

    private Spinner spScope;
    private TextInputEditText etKeyword;
    private Button btnCreate, btnCommit;
    private TextView tvProgress;
    private RecyclerView rv;
    private CheckAdapter adapter;
    private final List<StockCheckDto.Item> items = new ArrayList<>();
    private StockCheckDto currentCheck;
    private ApiClient api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stock_check);

        api = new ApiClient(new SessionStore(this));

        spScope = findViewById(R.id.spScope);
        etKeyword = findViewById(R.id.etKeyword);
        btnCreate = findViewById(R.id.btnCreate);
        btnCommit = findViewById(R.id.btnCommit);
        tvProgress = findViewById(R.id.tvProgress);
        rv = findViewById(R.id.rv);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CheckAdapter();
        rv.setAdapter(adapter);

        spScope.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"全部", "按SKU", "按库位"}));
        btnCreate.setOnClickListener(v -> createCheck());
        btnCommit.setOnClickListener(v -> commit());
    }

    private void createCheck() {
        String[] scopes = {"ALL", "SKU", "LOCATION"};
        String scope = scopes[spScope.getSelectedItemPosition()];
        String keyword = etKeyword.getText() == null ? null : etKeyword.getText().toString().trim();

        Map<String, String> body = new HashMap<>();
        body.put("scope", scope);
        if (keyword != null && !keyword.isEmpty()) body.put("keyword", keyword);

        btnCreate.setEnabled(false);
        new Thread(() -> {
            try {
                ApiResponse<StockCheckDto> resp =
                    api.post("/api/stock-checks", body, StockCheckDto.class);
                currentCheck = resp.data;
                items.clear();
                if (currentCheck != null && currentCheck.items != null) {
                    items.addAll(currentCheck.items);
                }
                runOnUiThread(() -> {
                    adapter.notifyDataSetChanged();
                    btnCreate.setEnabled(true);
                    updateProgress();
                });
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    btnCreate.setEnabled(true);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void commit() {
        if (currentCheck == null) {
            Toast.makeText(this, "请先创建盘点单", Toast.LENGTH_SHORT).show();
            return;
        }
        btnCommit.setEnabled(false);
        new Thread(() -> {
            try {
                // 逐条提交实盘数
                for (int i = 0; i < items.size(); i++) {
                    StockCheckDto.Item it = items.get(i);
                    if (it.countedQty == null) continue;
                    Map<String, Object> body = new HashMap<>();
                    body.put("countedQty", it.countedQty);
                    api.post("/api/stock-checks/" + currentCheck.id +
                            "/items/" + it.id + "/count", body, Object.class);
                }
                // 提交盘点
                api.post("/api/stock-checks/" + currentCheck.id + "/commit", null, Object.class);
                runOnUiThread(() -> {
                    Toast.makeText(this, "盘点已提交", Toast.LENGTH_LONG).show();
                    btnCommit.setEnabled(true);
                    finish();
                });
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    btnCommit.setEnabled(true);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void updateProgress() {
        if (currentCheck == null) { tvProgress.setText(""); return; }
        int counted = 0;
        for (StockCheckDto.Item it : items) if (it.countedQty != null) counted++;
        tvProgress.setText(String.format("进度 %d/%d 差 %d",
                counted, currentCheck.totalCount, currentCheck.diffCount));
    }

    private class CheckAdapter extends RecyclerView.Adapter<CheckAdapter.VH> {
        @Override public VH onCreateViewHolder(ViewGroup p, int vt) {
            View v = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_stock_check_row, p, false);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(VH h, int pos) {
            StockCheckDto.Item it = items.get(pos);
            h.code.setText(it.code);
            h.sku.setText(it.sku);
            h.system.setText("系统 " + it.systemQty);
            h.counted.setText(it.countedQty == null ? "" : String.valueOf(it.countedQty));
            h.counted.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    try {
                        it.countedQty = s.toString().isEmpty() ? null : Integer.parseInt(s.toString());
                    } catch (NumberFormatException e) {
                        it.countedQty = null;
                    }
                    updateProgress();
                }
            });
        }
        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView code, sku, system;
            TextInputEditText counted;
            VH(View v) {
                super(v);
                code = v.findViewById(R.id.tvCode);
                sku = v.findViewById(R.id.tvSku);
                system = v.findViewById(R.id.tvSystem);
                counted = v.findViewById(R.id.etCounted);
            }
        }
    }
}