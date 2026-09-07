package com.wms.pda.ui.outbound;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.wms.pda.R;
import com.wms.pda.data.SessionStore;
import com.wms.pda.model.ApiResponse;
import com.wms.pda.model.OutboundOrderDto;
import com.wms.pda.net.ApiClient;
import com.wms.pda.net.ApiException;
import com.wms.pda.util.BeepUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 出库扫码:
 * 1. 进入页 → 弹"选择/创建出库单"对话框(选 DRAFT 单或新建空草稿)
 * 2. 选/建后进入扫码页:扫到的码调 /api/outbound/orders/{id}/scan 加入单
 * 3. 提交时调 /api/outbound/orders/{id}/commit
 * 4. 顶栏显示单号 + 状态徽章
 */
public class OutboundScanActivity extends AppCompatActivity {

    private TextView tvOrderLabel, tvStatus;
    private TextInputEditText etCode;
    private Button btnSubmit, btnCancel;
    private TextView tvCount;
    private RecyclerView rv;
    private ScanAdapter adapter;
    private final java.util.Map<Integer, ScanRow> rows = new java.util.LinkedHashMap<>();

    private ApiClient api;
    private Integer orderId;          // 当前选中的草稿单 id(null 表示尚未选)
    private String orderNo;           // 当前单号(显示用)
    private String orderStatus;       // DRAFT / COMMITTED

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_outbound_scan);

        api = new ApiClient(new SessionStore(this));
        BeepUtil.init(this);

        tvOrderLabel = findViewById(R.id.tvOrderLabel);
        tvStatus = findViewById(R.id.tvStatus);
        etCode = findViewById(R.id.etCode);
        btnSubmit = findViewById(R.id.btnSubmit);
        btnCancel = findViewById(R.id.btnCancel);
        tvCount = findViewById(R.id.tvCount);
        rv = findViewById(R.id.rvItems);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ScanAdapter();
        rv.setAdapter(adapter);

        findViewById(R.id.btnScan).setOnClickListener(v -> doScan());
        etCode.setOnEditorActionListener((v, id, e) -> { doScan(); return true; });
        btnSubmit.setOnClickListener(v -> doCommit());
        btnCancel.setOnClickListener(v -> doCancelOrder());

        updateHeader();
        // 进入页:弹选/建单对话框
        showOrderPicker();
    }

    /** 弹"选/建单"对话框。选完 / 建完 setContentView 已是扫码页(本方法 onCreate 已设),只需更新状态 */
    private void showOrderPicker() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_outbound_order_picker, null);
        RecyclerView rvOrders = dialogView.findViewById(R.id.rvOrders);
        TextView tvEmpty = dialogView.findViewById(R.id.tvEmpty);
        TextInputEditText etCustomer = dialogView.findViewById(R.id.etCustomer);
        TextInputEditText etExternalNo = dialogView.findViewById(R.id.etExternalNo);
        TextInputEditText etRemark = dialogView.findViewById(R.id.etRemark);
        Button btnCreate = dialogView.findViewById(R.id.btnCreate);

        List<OutboundOrderDto> drafts = new ArrayList<>();
        AlertDialog[] dialogHolder = new AlertDialog[1];
        dialogHolder[0] = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();
        PickerAdapter pickerAdapter = new PickerAdapter(drafts, order -> {
            this.orderId = order.id;
            this.orderNo = order.orderNo;
            this.orderStatus = order.status;
            dialogHolder[0].dismiss();
            updateHeader();
        });
        rvOrders.setLayoutManager(new LinearLayoutManager(this));
        rvOrders.setAdapter(pickerAdapter);
        // 共享的 dialog 句柄(lambda 闭包要求 effectively final,所以用 1-length 数组)
        final AlertDialog dialog = dialogHolder[0];

        // 加载草稿单
        new Thread(() -> {
            try {
                ApiResponse<java.util.Map<String, Object>> resp = api.get(
                        "/api/outbound/orders?status=DRAFT&page=1&pageSize=20",
                        (Class) java.util.HashMap.class);
                if (resp != null && resp.data != null) {
                    @SuppressWarnings("unchecked")
                    List<java.util.Map<String, Object>> rawList =
                            (List<java.util.Map<String, Object>>) resp.data.get("list");
                    if (rawList != null) {
                        for (java.util.Map<String, Object> m : rawList) {
                            OutboundOrderDto o = new OutboundOrderDto();
                            o.id = ((Number) m.get("id")).intValue();
                            o.orderNo = (String) m.get("orderNo");
                            o.customer = (String) m.get("customer");
                            o.externalNo = (String) m.get("externalNo");
                            o.remark = (String) m.get("remark");
                            o.operator = (String) m.get("operator");
                            o.totalQuantity = m.get("totalQuantity") == null ? 0 : ((Number) m.get("totalQuantity")).intValue();
                            o.itemCount = m.get("itemCount") == null ? 0 : ((Number) m.get("itemCount")).intValue();
                            o.status = (String) m.get("status");
                            o.createdAt = (String) m.get("createdAt");
                            drafts.add(o);
                        }
                    }
                }
            } catch (ApiException e) {
                // 忽略,显示空
            }
            runOnUiThread(() -> {
                tvEmpty.setVisibility(drafts.isEmpty() ? View.VISIBLE : View.GONE);
                pickerAdapter.notifyDataSetChanged();
            });
        }).start();

        btnCreate.setOnClickListener(v -> {
            String customer = etCustomer.getText() == null ? null : etCustomer.getText().toString().trim();
            String externalNo = etExternalNo.getText() == null ? null : etExternalNo.getText().toString().trim();
            String remark = etRemark.getText() == null ? null : etRemark.getText().toString().trim();
            Map<String, Object> body = new HashMap<>();
            if (customer != null && !customer.isEmpty()) body.put("customer", customer);
            if (externalNo != null && !externalNo.isEmpty()) body.put("externalNo", externalNo);
            if (remark != null && !remark.isEmpty()) body.put("remark", remark);
            btnCreate.setEnabled(false);
            new Thread(() -> {
                try {
                    ApiResponse<OutboundOrderDto> resp = api.post(
                            "/api/outbound/orders", body, OutboundOrderDto.class);
                    runOnUiThread(() -> {
                        if (resp != null && resp.data != null) {
                            this.orderId = resp.data.id;
                            this.orderNo = resp.data.orderNo;
                            this.orderStatus = resp.data.status;
                            Toast.makeText(this, "已创建草稿单", Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                            updateHeader();
                        } else {
                            btnCreate.setEnabled(true);
                            Toast.makeText(this, "创建失败", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (ApiException e) {
                    runOnUiThread(() -> {
                        btnCreate.setEnabled(true);
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        });

        dialog.show();
    }

    private void updateHeader() {
        if (orderNo != null) {
            tvOrderLabel.setText(getString(R.string.outbound_order_label) + ": " + orderNo);
            tvStatus.setText(orderStatus);
            tvStatus.setVisibility(View.VISIBLE);
        } else {
            tvOrderLabel.setText(R.string.outbound_order_label);
            tvStatus.setVisibility(View.GONE);
        }
    }

    private void doScan() {
        if (orderId == null) {
            Toast.makeText(this, "请先选/建出库单", Toast.LENGTH_SHORT).show();
            showOrderPicker();
            return;
        }
        String code = etCode.getText() == null ? "" : etCode.getText().toString().trim();
        if (code.isEmpty()) return;
        etCode.setText("");
        new Thread(() -> {
            try {
                Map<String, Object> body = new HashMap<>();
                body.put("code", code);
                // 调 /api/outbound/orders/{id}/scan 校验 + 累加
                ApiResponse<OutboundOrderDto> resp = api.post(
                        "/api/outbound/orders/" + orderId + "/scan", body, OutboundOrderDto.class);
                runOnUiThread(() -> {
                    if (resp == null || resp.data == null) {
                        BeepUtil.error();
                        return;
                    }
                    // 后端返回的 items 列表里有 productId/quantity,我们用之重建本地 rows
                    rows.clear();
                    if (resp.data.items != null) {
                        for (OutboundOrderDto.OutboundOrderItemDto it : resp.data.items) {
                            ScanRow row = new ScanRow();
                            row.productId = it.productId;
                            row.sku = it.sku;
                            row.name = it.productName;
                            row.qty = it.quantity == null ? 0 : it.quantity;
                            rows.put(row.productId, row);
                        }
                    }
                    orderStatus = resp.data.status;
                    updateHeader();
                    adapter.notifyDataSetChanged();
                    updateCount();
                    BeepUtil.success();
                });
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    BeepUtil.error();
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void doCommit() {
        if (orderId == null) {
            Toast.makeText(this, "请先选/建出库单", Toast.LENGTH_SHORT).show();
            return;
        }
        btnSubmit.setEnabled(false);
        new Thread(() -> {
            try {
                ApiResponse<OutboundOrderDto> resp = api.post(
                        "/api/outbound/orders/" + orderId + "/commit", null, OutboundOrderDto.class);
                runOnUiThread(() -> {
                    BeepUtil.success();
                    Toast.makeText(this, R.string.outbound_commit_success, Toast.LENGTH_LONG).show();
                    orderStatus = "COMMITTED";
                    updateHeader();
                    rows.clear();
                    adapter.notifyDataSetChanged();
                    updateCount();
                    btnSubmit.setEnabled(true);
                    // 提交完退出,回 Home
                    finish();
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

    private void doCancelOrder() {
        if (orderId == null) return;
        new AlertDialog.Builder(this)
                .setMessage(R.string.outbound_cancel_confirm)
                .setPositiveButton(R.string.outbound_cancel, (d, w) -> {
                    new Thread(() -> {
                        try {
                            api.post("/api/outbound/orders/" + orderId + "/cancel", null, Object.class);
                            runOnUiThread(() -> {
                                Toast.makeText(this, "已取消", Toast.LENGTH_SHORT).show();
                                finish();
                            });
                        } catch (ApiException e) {
                            runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    }).start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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

    /** 选单对话框的列表 adapter */
    private static class PickerAdapter extends RecyclerView.Adapter<PickerAdapter.VH> {
        interface OnPick { void pick(OutboundOrderDto o); }
        private final List<OutboundOrderDto> data;
        private final OnPick onPick;
        PickerAdapter(List<OutboundOrderDto> data, OnPick onPick) {
            this.data = data; this.onPick = onPick;
        }
        @Override public VH onCreateViewHolder(ViewGroup p, int vt) {
            View v = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_outbound_picker_row, p, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(VH h, int pos) {
            OutboundOrderDto o = data.get(pos);
            h.orderNo.setText(o.orderNo == null ? "(无单号)" : o.orderNo);
            String customer = (o.customer == null || o.customer.isEmpty()) ? "" : o.customer + " · ";
            String meta = customer
                    + "共 " + (o.totalQuantity == null ? 0 : o.totalQuantity) + " 件 · "
                    + (o.itemCount == null ? 0 : o.itemCount) + " 行";
            h.meta.setText(meta);
            h.itemView.setOnClickListener(v -> onPick.pick(o));
        }
        @Override public int getItemCount() { return data.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView orderNo, meta;
            VH(View v) { super(v); orderNo = v.findViewById(R.id.tvOrderNo); meta = v.findViewById(R.id.tvMeta); }
        }
    }

    // LinkedHashMap 引用 — 不需要单独嵌套类
}
