package com.wms.pda.ui.check;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.wms.pda.R;
import com.wms.pda.data.SessionStore;
import com.wms.pda.model.ApiResponse;
import com.wms.pda.model.StockCheckDto;
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
 * 库存盘点扫码页(P6 重写):
 *
 * 流程:
 *   1. 进入页 → 弹"选/建盘点单"对话框
 *      - 选:GET /api/stock-checks?status=DRAFT
 *      - 建:选 scope (ALL / SKU / LOCATION) + keyword + remark → POST /api/stock-checks
 *   2. 选/建后进入扫码页:全屏摄像头预览 + 顶单号栏 + 底"已扫 X 件"列表
 *   3. ML Kit 抓到 1 个码 → 调 POST /api/stock-checks/{id}/scan → 累加 countedQty
 *   4. 列表从后端响应 items 重建
 *   5. 提交:POST /api/stock-checks/{id}/commit → Toast 显示差异数 → finish
 *   6. 取消:POST /api/stock-checks/{id}/cancel → finish
 *
 * 摄像头由 ScanManager.attachBarcodeCamera 在 onResume 绑定,
 * ScanManager.detachBarcodeCamera 在 onPause 释放。
 */
public class StockCheckActivity extends AppCompatActivity implements ScannerCallback {

    private static final long RESUME_RESCAN_DELAY_MS = 600L;

    private ApiClient api;
    private SessionStore session;
    private ScanManager scanManager;

    /** 当前 DRAFT 盘点单 ID。 */
    private Integer checkId;
    /** 当前盘点单详情(每次扫到后刷新)。 */
    private StockCheckDto currentCheck;

    // ====== 扫码页视图 ======
    private TextView tvCheckNo, tvStatus, tvCount;
    private Button btnCommit, btnCancel;
    private RecyclerView rvItems;
    private PreviewView previewView;
    private CheckAdapter checkAdapter;

    /** 已扫的盘点行(从后端响应 items 重建,key = inventoryItemId)。 */
    private final Map<Integer, CheckRow> rows = new HashMap<>();
    private final List<CheckRow> rowList = new ArrayList<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable resumeRescanRunnable = this::startScanning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = new SessionStore(this);
        api = new ApiClient(session);
        scanManager = ScanManager.get(this);
        BeepUtil.init(this);

        showScanPage();
        showCheckPicker();
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
        setContentView(R.layout.activity_stock_check);
        tvCheckNo = findViewById(R.id.tvCheckNo);
        tvStatus = findViewById(R.id.tvStatus);
        tvCount = findViewById(R.id.tvCount);
        btnCommit = findViewById(R.id.btnCommit);
        btnCancel = findViewById(R.id.btnCancel);
        rvItems = findViewById(R.id.rvItems);
        previewView = findViewById(R.id.previewView);
        rvItems.setLayoutManager(new LinearLayoutManager(this));
        checkAdapter = new CheckAdapter();
        rvItems.setAdapter(checkAdapter);

        btnCommit.setOnClickListener(v -> doCommit());
        btnCancel.setOnClickListener(v -> doCancelCheck());

        boolean hasCheck = checkId != null && currentCheck != null;
        btnCommit.setEnabled(hasCheck && isDraft(currentCheck) && !rows.isEmpty());
        btnCancel.setEnabled(hasCheck && isDraft(currentCheck));
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
        if (checkId == null) {
            mainHandler.post(() -> Toast.makeText(this, "请先选/建盘点单", Toast.LENGTH_SHORT).show());
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
                ApiResponse<StockCheckDto> resp = api.post(
                        "/api/stock-checks/" + checkId + "/scan", body, StockCheckDto.class);
                final StockCheckDto c = (resp == null) ? null : resp.data;
                mainHandler.post(() -> {
                    if (c == null) {
                        BeepUtil.error();
                        return;
                    }
                    currentCheck = c;
                    refreshLines();
                    BeepUtil.success();
                });
            } catch (ApiException e) {
                final int httpCode = e.getCode();
                final String msg = e.getMessage();
                mainHandler.post(() -> {
                    BeepUtil.error();
                    // 404 = 码不在系统,409 = 状态不对,422 = 码不在本盘点单
                    if (httpCode == 404) {
                        Toast.makeText(this, R.string.outbound_code_not_in_check, Toast.LENGTH_LONG).show();
                    } else if (httpCode == 409) {
                        Toast.makeText(this, R.string.code_already_out, Toast.LENGTH_LONG).show();
                    } else if (httpCode == 422) {
                        Toast.makeText(this, R.string.outbound_code_not_in_check, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    }
                    resumeScanning();
                });
            }
        }).start();
    }

    // ============================================================
    // 选/建盘点单对话框
    // ============================================================

    private void showCheckPicker() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.activity_stock_check_scope, null, false);
        RecyclerView rvChecks = dialogView.findViewById(R.id.rvPickerChecks);
        TextView tvEmpty = dialogView.findViewById(R.id.tvPickerEmpty);
        RadioGroup rgScope = dialogView.findViewById(R.id.rgScope);
        TextInputEditText etKeyword = dialogView.findViewById(R.id.etPickerKeyword);
        TextInputEditText etRemark = dialogView.findViewById(R.id.etPickerRemark);
        Button btnCreate = dialogView.findViewById(R.id.btnCreate);

        List<StockCheckDto> drafts = new ArrayList<>();
        AlertDialog[] dialogHolder = new AlertDialog[1];
        PickerAdapter pickerAdapter = new PickerAdapter(drafts, check -> {
            dialogHolder[0].dismiss();
            loadCheck(check.id);
        });
        rvChecks.setLayoutManager(new LinearLayoutManager(this));
        rvChecks.setAdapter(pickerAdapter);

        dialogHolder[0] = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .setNegativeButton(R.string.stock_check_cancel, (d, w) -> {
                    d.dismiss();
                    if (checkId == null) finish();
                })
                .create();

        final AlertDialog dialog = dialogHolder[0];
        dialog.setOnShowListener(d -> loadChecks(drafts, pickerAdapter, tvEmpty));

        btnCreate.setOnClickListener(v -> {
            String scope;
            int checkedId = rgScope.getCheckedRadioButtonId();
            if (checkedId == R.id.rbScopeSku) {
                scope = "SKU";
            } else if (checkedId == R.id.rbScopeLocation) {
                scope = "LOCATION";
            } else {
                scope = "ALL";
            }
            String keywordRaw = etKeyword.getText() == null ? null : etKeyword.getText().toString().trim();
            String remarkRaw = etRemark.getText() == null ? null : etRemark.getText().toString().trim();
            final String keyword = (keywordRaw == null || keywordRaw.isEmpty()) ? null : keywordRaw;
            final String remark = (remarkRaw == null || remarkRaw.isEmpty()) ? null : remarkRaw;
            btnCreate.setEnabled(false);
            new Thread(() -> {
                try {
                    Map<String, Object> body = new HashMap<>();
                    body.put("scope", scope);
                    if (keyword != null) body.put("keyword", keyword);
                    if (remark != null) body.put("remark", remark);
                    ApiResponse<StockCheckDto> resp =
                            api.post("/api/stock-checks", body, StockCheckDto.class);
                    final StockCheckDto created = (resp == null) ? null : resp.data;
                    mainHandler.post(() -> {
                        if (created != null) {
                            dialog.dismiss();
                            loadCheck(created.id);
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

    private void loadChecks(List<StockCheckDto> drafts, PickerAdapter adapter, TextView tvEmpty) {
        new Thread(() -> {
            try {
                ApiResponse<java.util.Map> resp = api.get(
                        "/api/stock-checks?status=DRAFT&page=1&pageSize=20",
                        (Class) java.util.HashMap.class);
                @SuppressWarnings("unchecked")
                List<java.util.Map<String, Object>> rawList =
                        resp == null || resp.data == null ? null : (List<java.util.Map<String, Object>>) resp.data.get("list");
                drafts.clear();
                if (rawList != null) {
                    for (java.util.Map<String, Object> m : rawList) {
                        StockCheckDto c = toStockCheckDto(m);
                        drafts.add(c);
                    }
                }
                mainHandler.post(() -> {
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(drafts.isEmpty() ? View.VISIBLE : View.GONE);
                });
            } catch (ApiException e) {
                mainHandler.post(() -> {
                    Toast.makeText(this,
                            getString(R.string.network_unavailable) + ": " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void loadCheck(Integer id) {
        this.checkId = id;
        new Thread(() -> {
            try {
                ApiResponse<StockCheckDto> resp = api.get(
                        "/api/stock-checks/" + id, StockCheckDto.class);
                final StockCheckDto c = (resp == null) ? null : resp.data;
                mainHandler.post(() -> {
                    currentCheck = c;
                    refreshLines();
                    renderOrderHeader();
                    boolean hasCheck = checkId != null && currentCheck != null;
                    btnCommit.setEnabled(hasCheck && isDraft(currentCheck) && !rows.isEmpty());
                    btnCancel.setEnabled(hasCheck && isDraft(currentCheck));
                });
            } catch (ApiException e) {
                mainHandler.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** 后端 list 接口里每个 StockCheck 不含 items,这里手工 map 出 DTO。 */
    private static StockCheckDto toStockCheckDto(java.util.Map<String, Object> m) {
        StockCheckDto c = new StockCheckDto();
        c.id = asInt(m.get("id"));
        c.checkNo = asStr(m.get("checkNo"));
        c.scope = asStr(m.get("scope"));
        c.scopeKeyword = asStr(m.get("scopeKeyword"));
        c.remark = asStr(m.get("remark"));
        c.status = asStr(m.get("status"));
        c.totalCount = asInt(m.get("totalCount"));
        c.countedCount = asInt(m.get("countedCount"));
        c.diffCount = asInt(m.get("diffCount"));
        return c;
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString().trim()); }
        catch (Exception e) { return null; }
    }

    private static String asStr(Object o) {
        return o == null ? null : o.toString();
    }

    // ============================================================
    // 提交 + 取消
    // ============================================================

    private void doCommit() {
        if (checkId == null || currentCheck == null) return;
        btnCommit.setEnabled(false);
        new Thread(() -> {
            try {
                ApiResponse<StockCheckDto> resp = api.post(
                        "/api/stock-checks/" + checkId + "/commit", null, StockCheckDto.class);
                final StockCheckDto c = (resp == null) ? null : resp.data;
                mainHandler.post(() -> {
                    BeepUtil.success();
                    int diff = c == null || c.diffCount == null ? 0 : c.diffCount;
                    Toast.makeText(this, getString(R.string.stock_check_committed_toast, diff),
                            Toast.LENGTH_LONG).show();
                    rows.clear();
                    rowList.clear();
                    checkAdapter.notifyDataSetChanged();
                    finish();
                });
            } catch (ApiException e) {
                mainHandler.post(() -> {
                    BeepUtil.error();
                    btnCommit.setEnabled(true);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void doCancelCheck() {
        if (checkId == null) return;
        new AlertDialog.Builder(this)
                .setMessage(R.string.stock_check_cancel_confirm)
                .setPositiveButton(R.string.stock_check_cancel, (d, w) -> {
                    new Thread(() -> {
                        try {
                            api.post("/api/stock-checks/" + checkId + "/cancel", null, Object.class);
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
        if (currentCheck != null && currentCheck.items != null) {
            for (StockCheckDto.Item it : currentCheck.items) {
                CheckRow row = new CheckRow();
                row.inventoryItemId = it.inventoryItemId;
                row.code = it.code;
                row.sku = it.sku;
                row.systemQty = it.systemQty == null ? 0 : it.systemQty;
                row.countedQty = it.countedQty == null ? 0 : it.countedQty;
                rows.put(row.inventoryItemId, row);
            }
        }
        rowList.addAll(rows.values());
        if (checkAdapter != null) checkAdapter.notifyDataSetChanged();
        updateCount();
    }

    private void updateCount() {
        int counted = 0;
        for (CheckRow r : rowList) {
            if (r.countedQty > 0) counted++;
        }
        if (tvCount != null) {
            tvCount.setText(getString(R.string.scanned_count, counted));
        }
    }

    private void renderOrderHeader() {
        if (tvCheckNo == null || tvStatus == null) return;
        if (currentCheck == null) {
            tvCheckNo.setText("");
            tvStatus.setText("");
            return;
        }
        tvCheckNo.setText(currentCheck.checkNo == null ? "" : currentCheck.checkNo);
        if ("COMMITTED".equals(currentCheck.status)) {
            tvStatus.setText(R.string.inbound_status_committed);
            tvStatus.setBackgroundColor(0xFF4CAF50);
        } else if ("CANCELLED".equals(currentCheck.status)) {
            tvStatus.setText(R.string.inbound_status_cancelled);
            tvStatus.setBackgroundColor(0xFFF44336);
        } else {
            tvStatus.setText(R.string.inbound_status_draft);
            tvStatus.setBackgroundColor(0xFFFFA000);
        }
    }

    private static boolean isDraft(StockCheckDto c) {
        return c != null && "DRAFT".equals(c.status);
    }

    // ============================================================
    // Adapter
    // ============================================================

    private static class CheckRow {
        Integer inventoryItemId;
        String code, sku;
        int systemQty;
        int countedQty;
    }

    private class CheckAdapter extends RecyclerView.Adapter<CheckAdapter.VH> {
        @Override
        public VH onCreateViewHolder(ViewGroup p, int vt) {
            View v = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_stock_check_row, p, false);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(VH h, int pos) {
            CheckRow r = rowList.get(pos);
            h.code.setText(r.code == null ? "" : r.code);
            h.sku.setText(r.sku == null ? "" : r.sku);
            h.system.setText("系统 " + r.systemQty);
            h.counted.setText("实盘 " + r.countedQty);
        }
        @Override public int getItemCount() { return rowList.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView code, sku, system, counted;
            VH(View v) {
                super(v);
                code = v.findViewById(R.id.tvCode);
                sku = v.findViewById(R.id.tvSku);
                system = v.findViewById(R.id.tvSystem);
                counted = v.findViewById(R.id.tvCounted);
            }
        }
    }

    /** 选单对话框的列表 adapter */
    private static class PickerAdapter extends RecyclerView.Adapter<PickerAdapter.VH> {
        interface OnPick { void pick(StockCheckDto c); }
        private final List<StockCheckDto> data;
        private final OnPick onPick;
        PickerAdapter(List<StockCheckDto> data, OnPick onPick) {
            this.data = data; this.onPick = onPick;
        }
        @Override public VH onCreateViewHolder(ViewGroup p, int vt) {
            View v = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_stock_check_picker_row, p, false);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(VH h, int pos) {
            StockCheckDto c = data.get(pos);
            h.checkNo.setText(c.checkNo == null ? "(无单号)" : c.checkNo);
            int total = c.totalCount == null ? 0 : c.totalCount;
            int diff = c.diffCount == null ? 0 : c.diffCount;
            h.meta.setText("范围 " + (c.scope == null ? "-" : c.scope)
                    + (c.scopeKeyword == null || c.scopeKeyword.isEmpty() ? "" : " (" + c.scopeKeyword + ")")
                    + " · 共 " + total + " 条"
                    + " · 差 " + diff + " 件");
            h.itemView.setOnClickListener(v -> onPick.pick(c));
        }
        @Override public int getItemCount() { return data.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView checkNo, meta;
            VH(View v) { super(v); checkNo = v.findViewById(R.id.tvCheckNo); meta = v.findViewById(R.id.tvMeta); }
        }
    }
}
