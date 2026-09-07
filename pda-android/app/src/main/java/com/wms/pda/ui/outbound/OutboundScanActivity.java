package com.wms.pda.ui.outbound;

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
import com.wms.pda.model.InventoryItemDto;
import com.wms.pda.model.OutboundRequest;
import com.wms.pda.model.ProductDto;
import com.wms.pda.net.ApiClient;
import com.wms.pda.net.ApiException;
import com.wms.pda.util.BeepUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** 出库扫码:扫 code → 查 inventory_item → IN_STOCK 加入 → 提交。 */
public class OutboundScanActivity extends AppCompatActivity {

    private TextInputEditText etCode, etCustomer, etRemark;
    private Button btnSubmit;
    private TextView tvCount;
    private RecyclerView rv;
    private ScanAdapter adapter;
    private final Map<Integer, ScanRow> rows = new LinkedHashMap<>();

    private ApiClient api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_outbound_scan);

        api = new ApiClient(new SessionStore(this));
        BeepUtil.init(this);

        etCode = findViewById(R.id.etCode);
        etCustomer = findViewById(R.id.etCustomer);
        etRemark = findViewById(R.id.etRemark);
        btnSubmit = findViewById(R.id.btnSubmit);
        tvCount = findViewById(R.id.tvCount);
        rv = findViewById(R.id.rvItems);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ScanAdapter();
        rv.setAdapter(adapter);
        updateCount();

        findViewById(R.id.btnScan).setOnClickListener(v -> doScan());
        btnSubmit.setOnClickListener(v -> doSubmit());
    }

    private void doScan() {
        String code = etCode.getText() == null ? "" : etCode.getText().toString().trim();
        if (code.isEmpty()) return;
        etCode.setText("");
        new Thread(() -> {
            try {
                ApiResponse<InventoryItemDto> resp =
                    api.get("/api/inventory/items/by-code/" + code, InventoryItemDto.class);
                if (resp == null || resp.data == null) {
                    throw new ApiException(404, "编码不存在: " + code);
                }
                InventoryItemDto it = resp.data;
                if (!"IN_STOCK".equals(it.status)) {
                    throw new ApiException(409, "该码已出库或已报废");
                }
                runOnUiThread(() -> {
                    BeepUtil.success();
                    ScanRow row = rows.get(it.productId);
                    if (row == null) {
                        row = new ScanRow();
                        row.productId = it.productId;
                        row.sku = it.sku;
                        row.name = it.productName;
                        row.qty = 0;
                        rows.put(it.productId, row);
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
        if (rows.isEmpty()) { Toast.makeText(this, "请先扫码", Toast.LENGTH_SHORT).show(); return; }
        btnSubmit.setEnabled(false);
        new Thread(() -> {
            try {
                OutboundRequest req = new OutboundRequest();
                req.customer = etCustomer.getText() == null ? null : etCustomer.getText().toString();
                req.remark = etRemark.getText() == null ? null : etRemark.getText().toString();
                req.items = new ArrayList<>();
                for (ScanRow r : rows.values()) {
                    OutboundRequest.Item it = new OutboundRequest.Item();
                    it.productId = r.productId;
                    it.quantity = r.qty;
                    req.items.add(it);
                }
                ApiResponse<Object> resp = api.post("/api/outbound/batch", req, Object.class);
                runOnUiThread(() -> {
                    BeepUtil.success();
                    Toast.makeText(this, R.string.outbound_success, Toast.LENGTH_LONG).show();
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