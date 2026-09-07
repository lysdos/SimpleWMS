package com.wms.pda.ui.outbound;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.wms.pda.R;
import com.wms.pda.data.SessionStore;
import com.wms.pda.model.ApiResponse;
import com.wms.pda.model.OutboundOrderDto;
import com.wms.pda.net.ApiClient;
import com.wms.pda.net.ApiException;
import com.wms.pda.scan.ScanManager;
import com.wms.pda.scan.ScanResult;
import com.wms.pda.scan.ScanType;
import com.wms.pda.scan.ScannerCallback;
import com.wms.pda.util.BeepUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 出库扫码(P6 重写):
 * 1. 进入页 → 弹"选择/创建出库单"对话框(选 DRAFT 单或新建空草稿)
 * 2. 选/建后进入扫码页:全屏摄像头预览 + 顶单号栏 + 底"已扫 X 件"列表
 * 3. ML Kit 抓到 1 个码 → 调 /api/outbound/orders/{id}/scan 加入单
 * 4. 列表从后端响应 items 重建
 * 5. 提交时调 /api/outbound/orders/{id}/commit
 * 6. 取消时调 /api/outbound/orders/{id}/cancel
 *
 * 摄像头由 ScanManager.attachBarcodeCamera 在 onResume 绑定,
 * ScanManager.detachBarcodeCamera 在 onPause 释放。
 */
public class OutboundScanActivity extends AppCompatActivity implements ScannerCallback {

    private static final long RESUME_RESCAN_DELAY_MS = 600L;

    private ApiClient api;
    private SessionStore session;
    private ScanManager scanManager;

    /** 当前 DRAFT 订单 ID(从选/建单得到)。 */
    private Integer orderId;
    /** 当前订单详情(每次扫到后刷新)。 */
    private OutboundOrderDto currentOrder;

    // ====== 扫码页视图 ======
    private TextView tvOrderNo, tvStatus, tvCount;
    private Button btnSubmit, btnCancel;
    private RecyclerView rvItems;
    private PreviewView previewView;
    private ScanAdapter scanAdapter;

    /** 已扫的明细列表(从后端响应 items 重建,key = productId)。 */
    private final Map<Integer, ScanRow> rows = new HashMap<>();
    private final List<ScanRow> rowList = new ArrayList<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 用于 onResume 后延迟重启扫描(等摄像头 ready)。 */
    private final Runnable resumeRescanRunnable = this::startScanning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = new SessionStore(this);
        api = new ApiClient(session);
        scanManager = ScanManager.get(this);
        BeepUtil.init(this);

        showScanPage();
        showOrderPicker();
    }

    // ============================================================
    // 生命周期 — 摄像头绑定/释放
    // ============================================================

    @Override
    protected void onResume() {
        super.onResume();
        if (previewView != null) {
            scanManager.attachBarcodeCamera(this, previewView);
            mainHandler.postDelayed(resumeRescanRunnable, RESUME_RESCAN_DELAY_MS);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(resumeRescanRunnable);
        scanManager.stopScan();
        scanManager.detachBarcodeCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanManager.stopScan();
        scanManager.detachBarcodeCamera();
    }

    private void startScanning() {
        scanManager.startScan(ScanType.BARCODE, this);
    }

    // ============================================================
    // 扫码页初始化
    // ============================================================

    private void showScanPage() {
        setContentView(R.layout.activity_outbound_scan);
        tvOrderNo = findViewById(R.id.tvOrderNo);
        tvStatus = findViewById(R.id.tvStatus);
        tvCount = findViewById(R.id.tvCount);
        btnSubmit = findViewById(R.id.btnSubmit);
        btnCancel = findViewById(R.id.btnCancel);
        rvItems = findViewById(R.id.rvItems);
        previewView = findViewById(R.id.previewView);
        rvItems.setLayoutManager(new LinearLayoutManager(this));
        scanAdapter = new ScanAdapter();
        rvItems.setAdapter(scanAdapter);

        btnSubmit.setOnClickListener(v -> doCommit());
        btnCancel.setOnClickListener(v -> doCancelOrder());

        boolean hasOrder = orderId != null && currentOrder != null;
        btnSubmit.setEnabled(hasOrder && isDraft(currentOrder) && !rows.isEmpty());
        btnCancel.setEnabled(hasOrder && isDraft(currentOrder));
        renderOrderHeader();
        refreshLines();
    }

    // ============================================================
    // ScannerCallback
    // ============================================================

    @Override
    public void onScanResult(@NonNull ScanResult result) {
        if (result.getType() != ScanType.BARCODE) return;
        String code = result.getCode();
        if (code == null || code.isEmpty()) return;
        if (orderId == null) {
            mainHandler.post(() -> Toast.makeText(this, "请先选/建出库单", Toast.LENGTH_SHORT).show());
            resumeScanning();
            return;
        }
        handleScannedCode(code);
    }

    @Override
    public void onError(int code, String msg) {
        mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onTimeout() {
        resumeScanning();
    }

    /** ML Kit 抓到一码后自动停;处理完后手动重启扫描。 */
    private void resumeScanning() {
        mainHandler.postDelayed(this::startScanning, 500L);
    }

    private void handleScannedCode(String code) {
        new Thread(() -> {
            try {
                Map<String, Object> body = new HashMap<>();
                body.put("code", code);
                ApiResponse<OutboundOrderDto> resp = api.post(
                        "/api/outbound/orders/" + orderId + "/scan", body, OutboundOrderDto.class);
                final OutboundOrderDto o = resp == null ? null : resp.data;
                mainHandler.post(() -> {
                    if (o == null) {
                        BeepUtil.error();
                        return;
                    }
                    currentOrder = o;
                    refreshLines();
                    BeepUtil.success();
                });
            } catch (ApiException e) {
                final int httpCode = e.getCode();
                final String msg = e.getMessage();
                mainHandler.post(() -> {
                    BeepUtil.error();
                    // 404 = 码不在系统,409 = 码已出库/报废
                    if (httpCode == 404) {
                        Toast.makeText(this, R.string.outbound_code_not_in_check, Toast.LENGTH_LONG).show();
                    } else if (httpCode == 409) {
                        Toast.makeText(this, R.string.code_already_out, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    }
                    resumeScanning();
                });
            }
        }).start();
    }

    // ============================================================
    // 选/建单对话框
    // ============================================================

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
            loadOrder(order.id);
            dialogHolder[0].dismiss();
        });
        rvOrders.setLayoutManager(new LinearLayoutManager(this));
        rvOrders.setAdapter(pickerAdapter);
        final AlertDialog dialog = dialogHolder[0];

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
            mainHandler.post(() -> {
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
                    final OutboundOrderDto created = (resp == null) ? null : resp.data;
                    mainHandler.post(() -> {
                        if (created != null) {
                            loadOrder(created.id);
                            dialog.dismiss();
                        } else {
                            btnCreate.setEnabled(true);
                            Toast.makeText(this, "创建失败", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (ApiException e) {
                    mainHandler.post(() -> {
                        btnCreate.setEnabled(true);
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        });

        dialog.show();
    }

    private void loadOrder(Integer id) {
        this.orderId = id;
        new Thread(() -> {
            try {
                ApiResponse<OutboundOrderDto> resp = api.get(
                        "/api/outbound/orders/" + id, OutboundOrderDto.class);
                final OutboundOrderDto o = (resp == null) ? null : resp.data;
                mainHandler.post(() -> {
                    currentOrder = o;
                    refreshLines();
                    renderOrderHeader();
                    boolean hasOrder = orderId != null && currentOrder != null;
                    btnSubmit.setEnabled(hasOrder && isDraft(currentOrder) && !rows.isEmpty());
                    btnCancel.setEnabled(hasOrder && isDraft(currentOrder));
                });
            } catch (ApiException e) {
                mainHandler.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ============================================================
    // 提交 + 取消
    // ============================================================

    private void doCommit() {
        if (orderId == null || currentOrder == null) return;
        btnSubmit.setEnabled(false);
        new Thread(() -> {
            try {
                ApiResponse<OutboundOrderDto> resp = api.post(
                        "/api/outbound/orders/" + orderId + "/commit", null, OutboundOrderDto.class);
                final OutboundOrderDto o = (resp == null) ? null : resp.data;
                mainHandler.post(() -> {
                    BeepUtil.success();
                    currentOrder = o;
                    Toast.makeText(this, R.string.outbound_commit_success, Toast.LENGTH_LONG).show();
                    rows.clear();
                    rowList.clear();
                    scanAdapter.notifyDataSetChanged();
                    refreshLines();
                    finish();
                });
            } catch (ApiException e) {
                mainHandler.post(() -> {
                    BeepUtil.error();
                    btnSubmit.setEnabled(true);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
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
                            mainHandler.post(this::finish);
                        } catch (ApiException e) {
                            mainHandler.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    }).start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ============================================================
    // 渲染 + 工具
    // ============================================================

    private void refreshLines() {
        rows.clear();
        rowList.clear();
        if (currentOrder != null && currentOrder.items != null) {
            for (OutboundOrderDto.OutboundOrderItemDto it : currentOrder.items) {
                ScanRow row = new ScanRow();
                row.productId = it.productId;
                row.sku = it.sku;
                row.name = it.productName;
                row.qty = it.quantity == null ? 0 : it.quantity;
                rows.put(row.productId, row);
            }
        }
        rowList.addAll(rows.values());
        if (scanAdapter != null) scanAdapter.notifyDataSetChanged();
        updateCount();
    }

    private void updateCount() {
        int total = 0;
        for (ScanRow r : rowList) total += r.qty;
        if (tvCount != null) {
            tvCount.setText(getString(R.string.scanned_count, total));
        }
    }

    private void renderOrderHeader() {
        if (tvOrderNo == null || tvStatus == null) return;
        if (currentOrder == null) {
            tvOrderNo.setText("");
            tvStatus.setText("");
            return;
        }
        tvOrderNo.setText(currentOrder.orderNo == null ? "" : currentOrder.orderNo);
        if ("COMMITTED".equals(currentOrder.status)) {
            tvStatus.setText(R.string.inbound_status_committed);
            tvStatus.setBackgroundColor(0xFF4CAF50);
        } else if ("CANCELLED".equals(currentOrder.status)) {
            tvStatus.setText(R.string.inbound_status_cancelled);
            tvStatus.setBackgroundColor(0xFFF44336);
        } else {
            tvStatus.setText(R.string.inbound_status_draft);
            tvStatus.setBackgroundColor(0xFFFFA000);
        }
    }

    private static boolean isDraft(OutboundOrderDto o) {
        return o != null && "DRAFT".equals(o.status);
    }

    // ============================================================
    // Adapter
    // ============================================================

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
            ScanRow r = rowList.get(pos);
            h.sku.setText(r.sku == null ? "" : r.sku);
            h.name.setText(r.name == null ? "" : r.name);
            h.qty.setText("×" + r.qty);
        }
        @Override public int getItemCount() { return rowList.size(); }

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
        @Override
        public void onBindViewHolder(VH h, int pos) {
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
}
