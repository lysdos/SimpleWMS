package com.wms.pda.ui.stocktake;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.wms.pda.R;
import com.wms.pda.data.model.Enums;
import com.wms.pda.data.model.StocktakeOrder;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;
import com.wms.pda.ui.common.StatusPill;
import com.wms.pda.scan.ScanManager;
import com.wms.pda.scan.ScanResult;
import com.wms.pda.scan.ScanType;
import com.wms.pda.scan.ScannerCallback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 盘点单详情:对接后端 /api/stock-checks/{id}。
 * RFID/条码双通道扫码(带停止,互斥);扫到单外码自动记盘盈;逐行删除。
 */
public class StocktakeDetailActivity extends AppCompatActivity {

    private Integer dbId;
    private LinearLayout llLines;
    private TextView tvProgress;
    private MaterialButton btnSubmit;

    private MaterialButton btnRfidScan, btnRfidStop, btnBarcodeScan, btnBarcodeStop;
    private ScanType activeScanType;      // null = 空闲
    private boolean docDone;              // 已提交/已取消时禁用扫码
    private int lastTotal, lastCounted;   // 最近一次渲染的统计(提交确认文案用)
    /** 本会话内已尝试/已成功的码:防止持续扫描时同一码反复上报 */
    private final Set<String> triedCodes = new HashSet<>();
    private final Set<String> scannedCodes = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stocktake_detail);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        dbId = getIntent().getIntExtra("orderDbId", -1);
        llLines = findViewById(R.id.ll_lines);
        tvProgress = findViewById(R.id.tv_progress);
        btnSubmit = findViewById(R.id.btn_submit);

        btnRfidScan = findViewById(R.id.btn_rfid_scan);
        btnRfidStop = findViewById(R.id.btn_rfid_stop);
        btnBarcodeScan = findViewById(R.id.btn_barcode_scan);
        btnBarcodeStop = findViewById(R.id.btn_barcode_stop);
        btnRfidScan.setOnClickListener(v -> startRfidScan());
        btnRfidStop.setOnClickListener(v -> stopScan());
        btnBarcodeScan.setOnClickListener(v -> startBarcodeScan());
        btnBarcodeStop.setOnClickListener(v -> stopScan());

        btnSubmit.setOnClickListener(v -> confirmAndSubmit());
    }

    @Override
    protected void onResume() { super.onResume(); render(); }

    @Override
    protected void onPause() {
        super.onPause();
        stopScan(); // 离开页面时停止扫码
    }

    // ============================================================
    // RFID 扫码
    // ============================================================

    private void startRfidScan() {
        if (activeScanType == ScanType.BARCODE) stopScan();
        activeScanType = ScanType.RFID_EPC;
        updateButtonStates();

        ScanManager.get(this).startScan(ScanType.RFID_EPC, new ScannerCallback() {
            @Override
            public void onScanResult(ScanResult result) {
                handleScanResult(result == null ? null : result.getCode());
            }

            @Override
            public void onError(int code, String msg) {
                stopScan();
                Toast.makeText(StocktakeDetailActivity.this,
                        code == -100 ? msg : "扫码异常: " + msg, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onTimeout() {
                stopScan();
                Toast.makeText(StocktakeDetailActivity.this,
                        "RFID 扫码超时,未读到标签", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ============================================================
    // 条码扫码
    // ============================================================

    private void startBarcodeScan() {
        if (activeScanType == ScanType.RFID_EPC) stopScan();
        activeScanType = ScanType.BARCODE;
        updateButtonStates();

        ScanManager.get(this).startScan(ScanType.BARCODE, new ScannerCallback() {
            @Override
            public void onScanResult(ScanResult result) {
                handleScanResult(result == null ? null : result.getCode());
            }

            @Override
            public void onError(int code, String msg) {
                stopScan();
                Toast.makeText(StocktakeDetailActivity.this,
                        code == -100 ? msg : "扫码异常: " + msg, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onTimeout() {
                stopScan();
                Toast.makeText(StocktakeDetailActivity.this,
                        "条码扫码超时,未读到码", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ============================================================
    // 停止扫码 / 按钮状态
    // ============================================================

    private void stopScan() {
        ScanManager.get(this).stopScan();
        activeScanType = null;
        updateButtonStates();
    }

    private void updateButtonStates() {
        if (docDone) {
            setScanButton(btnRfidScan, btnRfidStop, false);
            setScanButton(btnBarcodeScan, btnBarcodeStop, false);
            return;
        }
        if (activeScanType == null) {
            setScanButton(btnRfidScan, btnRfidStop, true);
            setScanButton(btnBarcodeScan, btnBarcodeStop, true);
        } else if (activeScanType == ScanType.RFID_EPC) {
            setScanButton(btnRfidScan, btnRfidStop, true);
            btnRfidScan.setVisibility(View.GONE);
            btnRfidStop.setVisibility(View.VISIBLE);
            setScanButton(btnBarcodeScan, btnBarcodeStop, false);
        } else {
            setScanButton(btnBarcodeScan, btnBarcodeStop, true);
            btnBarcodeScan.setVisibility(View.GONE);
            btnBarcodeStop.setVisibility(View.VISIBLE);
            setScanButton(btnRfidScan, btnRfidStop, false);
        }
    }

    /** enabled=true:开始按钮可见可点、停止隐藏;false:禁用灰显 */
    private void setScanButton(MaterialButton scan, MaterialButton stop, boolean enabled) {
        int colorPrimary = ContextCompat.getColor(this, R.color.primary);
        int colorOnPrimary = ContextCompat.getColor(this, R.color.on_primary);
        int colorDisabled = ContextCompat.getColor(this, R.color.text_hint);
        ColorStateList disabledBg = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.divider));
        ColorStateList enabledBg = ColorStateList.valueOf(colorPrimary);

        scan.setVisibility(View.VISIBLE);
        stop.setVisibility(View.GONE);
        scan.setEnabled(enabled);
        scan.setClickable(enabled);
        scan.setTextColor(enabled ? colorOnPrimary : colorDisabled);
        scan.setBackgroundTintList(enabled ? enabledBg : disabledBg);
    }

    // ============================================================
    // 扫码结果提交
    // ============================================================

    private void handleScanResult(String code) {
        if (code == null || code.isEmpty()) return;
        String canon = code.trim();
        // 会话内去重:持续扫描时同一码 2 秒后会重复上报,静默跳过
        if (triedCodes.contains(canon) || scannedCodes.contains(canon)) return;
        triedCodes.add(canon);
        RepositoryProvider.get().submitStocktakeScan(String.valueOf(dbId), canon,
                new WmsCallback<StocktakeOrder>() {
                    @Override public void onSuccess(StocktakeOrder o) {
                        scannedCodes.add(canon);
                        boolean overage = false;
                        if (o != null && o.lines != null) {
                            for (StocktakeOrder.Line l : o.lines) {
                                if (canon.equals(l.code == null ? null : l.code.trim())
                                        && l.systemQty != null && l.systemQty == 0) {
                                    overage = true;
                                    break;
                                }
                            }
                        }
                        Toast.makeText(StocktakeDetailActivity.this,
                                overage ? "已记为盘盈: " + canon
                                        : getString(R.string.stocktake_located_fmt, canon),
                                Toast.LENGTH_SHORT).show();
                        render();
                    }
                    @Override public void onError(int code, String msg) {
                        if (msg != null && msg.contains("已盘过")) {
                            Toast.makeText(StocktakeDetailActivity.this,
                                    "该码已盘过", Toast.LENGTH_SHORT).show();
                        } else if (msg != null && msg.contains("码不存在")) {
                            Toast.makeText(StocktakeDetailActivity.this,
                                    "该码未入库,已跳过", Toast.LENGTH_SHORT).show();
                        } else {
                            // 网络/服务端瞬时失败:放开去重,允许用户重扫该码
                            triedCodes.remove(canon);
                            Toast.makeText(StocktakeDetailActivity.this,
                                    "扫码失败: " + msg, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    // ============================================================
    // 行删除
    // ============================================================

    private void confirmDeleteLine(StocktakeOrder.Line l) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.stocktake_row_delete)
                .setMessage(getString(R.string.stocktake_row_delete_confirm,
                        l.sku == null ? "" : l.sku))
                .setPositiveButton(R.string.action_confirm, (d, w) ->
                        RepositoryProvider.get().deleteStocktakeItem(String.valueOf(dbId),
                                String.valueOf(l.id),
                                new WmsCallback<Void>() {
                                    @Override public void onSuccess(Void v) {
                                        // 允许删掉后重新扫码记盘盈:把该码从会话去重集合里移除
                                        if (l.code != null) {
                                            String c = l.code.trim();
                                            triedCodes.remove(c);
                                            scannedCodes.remove(c);
                                        }
                                        Toast.makeText(StocktakeDetailActivity.this,
                                                "已删除", Toast.LENGTH_SHORT).show();
                                        render();
                                    }
                                    @Override public void onError(int code, String msg) {
                                        Toast.makeText(StocktakeDetailActivity.this,
                                                "删除失败: " + msg, Toast.LENGTH_SHORT).show();
                                    }
                                }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmAndSubmit() {
        int remain = Math.max(0, lastTotal - lastCounted);
        String msg = remain > 0
                ? getString(R.string.stocktake_submit_confirm_unfinished, remain)
                : getString(R.string.stocktake_submit_confirm_all);
        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_complete_stocktake)
                .setMessage(msg)
                .setPositiveButton(R.string.action_confirm, (d, w) ->
                        RepositoryProvider.get().commitStocktake(String.valueOf(dbId),
                                new WmsCallback<StocktakeOrder>() {
                                    @Override public void onSuccess(StocktakeOrder o) {
                                        Toast.makeText(StocktakeDetailActivity.this, R.string.msg_submitted, Toast.LENGTH_SHORT).show();
                                        finish();
                                    }
                                    @Override public void onError(int code, String msg) {
                                        Toast.makeText(StocktakeDetailActivity.this, "提交失败: " + msg, Toast.LENGTH_SHORT).show();
                                    }
                                }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void render() {
        if (dbId == null || dbId < 0) { finish(); return; }
        RepositoryProvider.get().getStocktake(String.valueOf(dbId),
                new WmsCallback<StocktakeOrder>() {
                    @Override public void onSuccess(StocktakeOrder o) {
                        if (o == null) { finish(); return; }
                        doRender(o);
                    }
                    @Override public void onError(int code, String msg) {
                        Toast.makeText(StocktakeDetailActivity.this, "加载失败: " + msg, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /** 盘盈行:账面 0、实盘 >0(扫到了盘点范围之外的单元)。口径与后端 StockCheckService.isSurplus 一致。 */
    private static boolean isSurplusLine(StocktakeOrder.Line l) {
        int sys = l.systemQty == null ? 0 : l.systemQty;
        return sys == 0 && l.countedQty != null && l.countedQty > 0;
    }

    /** 构建一行盘点明细(明细区与盘盈区共用)。docLocked = 单据已结束,行不可删。 */
    private View buildLineRow(LayoutInflater li, StocktakeOrder.Line l, boolean docLocked) {
        View row = li.inflate(R.layout.item_stocktake_row, llLines, false);
        TextView tvSku = row.findViewById(R.id.tv_sku);
        TextView tvSub = row.findViewById(R.id.tv_sub);
        TextView tvBook = row.findViewById(R.id.tv_book);
        TextView etActual = row.findViewById(R.id.et_actual);
        TextView tvDiff = row.findViewById(R.id.tv_diff);
        TextView tvPill = row.findViewById(R.id.tv_pill);

        TextView btnDel = row.findViewById(R.id.btn_row_del);
        if (btnDel != null) {
            boolean locked = docLocked || l.id == null;
            btnDel.setVisibility(locked ? View.GONE : View.VISIBLE);
            if (!locked) btnDel.setOnClickListener(v -> confirmDeleteLine(l));
        }

        tvSku.setText(l.sku == null ? "?" : l.sku);
        tvSub.setText((l.productName == null ? "" : l.productName)
                + " · 码 " + (l.code == null ? "—" : l.code)
                + " · " + (l.locationCode == null ? "—" : l.locationCode));
        tvBook.setText(l.systemQty == null ? "—" : String.valueOf(l.systemQty));
        etActual.setText(l.countedQty == null ? "" : String.valueOf(l.countedQty));
        etActual.setEnabled(false);

        int sys = l.systemQty == null ? 0 : l.systemQty;
        int cnt = l.countedQty == null ? -1 : l.countedQty;
        if (cnt < 0) {
            tvDiff.setText("—");
            tvDiff.setTextColor(0xFF9AA3B2);
            tvPill.setText(R.string.stocktake_status_uncounted);
            tvPill.setBackgroundResource(R.drawable.bg_pill_draft);
        } else {
            int d = cnt - sys;
            String sign = d > 0 ? "+" : "";
            tvDiff.setText(sign + d);
            if (d == 0) {
                tvDiff.setTextColor(0xFF27AE60);
                tvPill.setText(R.string.stocktake_status_consistent);
                tvPill.setBackgroundResource(R.drawable.bg_pill_success);
            } else if (d > 0) {
                tvDiff.setTextColor(0xFF27AE60);
                tvPill.setText(R.string.stocktake_status_over);
                tvPill.setBackgroundResource(R.drawable.bg_pill_success);
            } else {
                tvDiff.setTextColor(0xFFE74C3C);
                tvPill.setText(R.string.stocktake_status_short);
                tvPill.setBackgroundResource(R.drawable.bg_pill_warning);
            }
        }
        return row;
    }

    private void doRender(StocktakeOrder o) {
        TextView tvId = findViewById(R.id.tv_id);
        TextView tvStatus = findViewById(R.id.tv_status);
        TextView tvScope = findViewById(R.id.tv_warehouse);
        TextView tvMode = findViewById(R.id.tv_mode);
        TextView tvOperator = findViewById(R.id.tv_operator);
        TextView tvCreated = findViewById(R.id.tv_created);

        tvId.setText(o.id);
        StatusPill.apply(tvStatus, o.status);
        String kw = (o.scopeKeyword == null || o.scopeKeyword.isEmpty()) ? "—" : o.scopeKeyword;
        tvScope.setText("盘点范围: " + kw);
        String scopeLabel = "ALL".equals(o.scope) ? "全部在库单元"
                : "SKU".equals(o.scope) ? "按SKU"
                : "LOCATION".equals(o.scope) ? "按库位"
                : "WAREHOUSE".equals(o.scope) ? "按仓库"
                : (o.scope == null ? "—" : o.scope);
        tvMode.setText("盘点方式: " + scopeLabel);
        tvOperator.setText("创建人: " + (o.createdBy == null ? "—" : o.createdBy));
        // 后端给的是 ISO(yyyy-MM-ddTHH:mm:ss),详情页保留到秒,只把 T 换成空格
        tvCreated.setText(getString(R.string.created_at_fmt,
                o.createdAt == null ? "—" : o.createdAt.replace("T", " ")));

        llLines.removeAllViews();
        LayoutInflater li = LayoutInflater.from(this);

        // 盘盈行(账面 0、实盘 >0,即扫到盘点范围外的单元)单独成区,不混在应盘明细里 ——
        // 混在一起看不出哪些是多出来的,已盘计数也没法解释(口径见 StockCheckService.isSurplus)。
        List<StocktakeOrder.Line> detailLines = new ArrayList<>();
        List<StocktakeOrder.Line> surplusLines = new ArrayList<>();
        if (o.lines != null) {
            for (StocktakeOrder.Line l : o.lines) {
                if (isSurplusLine(l)) surplusLines.add(l); else detailLines.add(l);
            }
        }

        if (detailLines.isEmpty() && surplusLines.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("盘点单无明细");
            empty.setTextColor(0xFF9AA3B2);
            empty.setPadding(24, 24, 24, 24);
            llLines.addView(empty);
        } else {
            boolean docLocked = o.status == Enums.DocStatus.DONE || o.status == Enums.DocStatus.CANCELLED;
            for (StocktakeOrder.Line l : detailLines) {
                llLines.addView(buildLineRow(li, l, docLocked));
            }
            if (!surplusLines.isEmpty()) {
                TextView h = new TextView(this);
                h.setText(getString(R.string.stocktake_surplus_header, surplusLines.size()));
                h.setTextSize(13);
                h.setTextColor(0xFF9AA3B2);
                h.setPadding(24, 20, 24, 4);
                llLines.addView(h);
                for (StocktakeOrder.Line l : surplusLines) {
                    llLines.addView(buildLineRow(li, l, docLocked));
                }
            }
        }

        docDone = o.status == Enums.DocStatus.DONE || o.status == Enums.DocStatus.CANCELLED;
        btnSubmit.setEnabled(!docDone);
        updateButtonStates();

        int total = o.totalCount == null ? 0 : o.totalCount;
        int counted = o.countedCount == null ? 0 : o.countedCount;
        lastTotal = total;
        lastCounted = counted;
        tvProgress.setText(getString(R.string.stocktake_progress_fmt, counted, total));
    }
}