package com.wms.pda.ui.inbound;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.wms.pda.R;
import com.wms.pda.data.SessionStore;
import com.wms.pda.model.ApiResponse;
import com.wms.pda.model.InboundRequest;
import com.wms.pda.model.InventoryItemDto;
import com.wms.pda.model.ProductDto;
import com.wms.pda.net.ApiClient;
import com.wms.pda.net.ApiException;
import com.wms.pda.scan.ScanManager;
import com.wms.pda.scan.ScanType;
import com.wms.pda.scan.ScannerCallback;
import com.wms.pda.scan.ScanResult;
import com.wms.pda.util.BeepUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 入库扫码:扫 code → 查 Product → 累加数量 → 提交。 */
public class InboundScanActivity extends AppCompatActivity {

    private TextInputEditText etCode, etRemark;
    private Button btnScan, btnSubmit;
    private TextView tvCount;
    private RecyclerView rv;
    private ScanAdapter adapter;
    private final Map<Integer, ScanRow> rows = new LinkedHashMap<>();  // productId -> row

    private ApiClient api;
    private SessionStore session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inbound_scan);

        session = new SessionStore(this);
        api = new ApiClient(session);
        BeepUtil.init(this);

        etCode = findViewById(R.id.etCode);
        etRemark = findViewById(R.id.etRemark);
        btnScan = findViewById(R.id.btnScan);
        btnSubmit = findViewById(R.id.btnSubmit);
        tvCount = findViewById(R.id.tvCount);
        rv = findViewById(R.id.rvItems);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ScanAdapter();
        rv.setAdapter(adapter);
        updateCount();

        btnScan.setOnClickListener(v -> doScan());
        etCode.setOnEditorActionListener((v, id, e) -> { doScan(); return true; });
        btnSubmit.setOnClickListener(v -> doSubmit());
    }

    private void doScan() {
        String code = etCode.getText() == null ? "" : etCode.getText().toString().trim();
        if (code.isEmpty()) return;
        etCode.setText("");
        new Thread(() -> {
            try {
                // 1) 先查 inventory_item 是否已有此码(防重复)
                ApiResponse<InventoryItemDto> itemResp =
                    api.get("/api/inventory/items/by-code/" + code, InventoryItemDto.class);
                if (itemResp != null && itemResp.data != null) {
                    runOnUiThread(() -> {
                        BeepUtil.error();
                        Toast.makeText(this, R.string.code_duplicate, Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                // 2) 查 Product
                ApiResponse<ProductDto> prodResp =
                    api.get("/api/inventory/items/by-code/" + code + "/product", ProductDto.class);
                if (prodResp == null || prodResp.data == null) {
                    throw new ApiException(404, "商品未找到");
                }
                ProductDto p = prodResp.data;
                runOnUiThread(() -> {
                    BeepUtil.success();
                    ScanRow row = rows.get(p.id);
                    if (row == null) {
                        row = new ScanRow();
                        row.productId = p.id;
                        row.sku = p.sku;
                        row.name = p.name;
                        row.qty = 0;
                        rows.put(p.id, row);
                    }
                    row.qty += 1;
                    adapter.notifyDataSetChanged();
                    updateCount();
                });
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    BeepUtil.error();
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void doSubmit() {
        if (rows.isEmpty()) {
            Toast.makeText(this, "请先扫码", Toast.LENGTH_SHORT).show();
            return;
        }
        btnSubmit.setEnabled(false);
        new Thread(() -> {
            try {
                InboundRequest req = new InboundRequest();
                req.remark = etRemark.getText() == null ? null : etRemark.getText().toString();
                req.items = new ArrayList<>();
                for (ScanRow r : rows.values()) {
                    InboundRequest.Item it = new InboundRequest.Item();
                    it.productId = r.productId;
                    it.quantity = r.qty;
                    req.items.add(it);
                }
                ApiResponse<Object> resp = api.post("/api/inbound/batch", req, Object.class);
                runOnUiThread(() -> {
                    BeepUtil.success();
                    Toast.makeText(this, R.string.inbound_success, Toast.LENGTH_LONG).show();
                    rows.clear();
                    adapter.notifyDataSetChanged();
                    updateCount();
                    btnSubmit.setEnabled(true);
                });
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    BeepUtil.error();
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    btnSubmit.setEnabled(true);
                });
            }
        }).start();
    }

    private void updateCount() {
        int total = 0;
        for (ScanRow r : rows.values()) total += r.qty;
        tvCount.setText(getString(R.string.scanned_count, total));
    }

    private static class ScanRow {
        Integer productId;
        String sku, name;
        int qty;
    }

    private class ScanAdapter extends RecyclerView.Adapter<ScanAdapter.VH> {
        @Override
        public VH onCreateViewHolder(ViewGroup parent, int vt) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_scan_row, parent, false);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(VH h, int pos) {
            ScanRow r = (ScanRow) rows.values().toArray()[pos];
            h.sku.setText(r.sku);
            h.name.setText(r.name);
            h.qty.setText("×" + r.qty);
        }
        @Override public int getItemCount() { return rows.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView sku, name, qty;
            VH(View v) {
                super(v);
                sku = v.findViewById(R.id.tvSku);
                name = v.findViewById(R.id.tvName);
                qty = v.findViewById(R.id.tvQty);
            }
        }
    }
}