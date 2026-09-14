package com.wms.pda.ui.outbound;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import android.content.res.ColorStateList;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.wms.pda.R;
import com.wms.pda.data.model.Enums;
import com.wms.pda.data.model.OutboundOrder;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;
import com.wms.pda.scan.ScanManager;
import com.wms.pda.scan.ScanResult;
import com.wms.pda.scan.ScanType;
import com.wms.pda.scan.ScannerCallback;
import com.wms.pda.ui.common.StatusPill;
import com.wms.pda.util.BeepUtil;

import java.util.HashSet;
import java.util.Set;

/**
 * 出库单详情:展示后端实际字段;双模式扫码(RFID + 条码) / 提交。
 *
 * 扫码智能处理:
 *   - 扫到当前出库单的 SKU → 正常累加
 *   - 扫到在库但非当前出库单 SKU → 提示"在库但非当前出库单"后跳过
 *   - 扫到非在库的码 → 提示"未入库"后跳过
 */
public class OutboundDetailActivity extends AppCompatActivity {

    private Integer dbId;
    private LinearLayout llLines;
    private MaterialButton btnComplete;
    private TextView tvProgress;
    private ProgressBar progressBar;
    private TextView tvScannedTitle;
    private LinearLayout llScanned;

    // RFID 扫码按钮对
    private MaterialButton btnRfidScan, btnRfidStop;
    // 条码扫码按钮对
    private MaterialButton btnBarcodeScan, btnBarcodeStop;

    /** 已成功扫描的 24 位码(用于避免重复提示) */
    private final Set<String> scannedCodes = new HashSet<>();

    /** 当前出库单的总数量(用于进度计算) */
    private int totalQty = 0;
    private int scannedQty = 0;

    /** 当前活跃的扫码类型(null = 未在扫码) */
    private ScanType activeScanType = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_outbound_detail);

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        dbId = getIntent().getIntExtra("orderDbId", -1);
        llLines = findViewById(R.id.ll_lines);
        btnComplete = findViewById(R.id.btn_complete);
        tvProgress = findViewById(R.id.tv_progress);
        progressBar = findViewById(R.id.progress_bar);
        tvScannedTitle = findViewById(R.id.tv_scanned_title);
        llScanned = findViewById(R.id.ll_scanned);

        // RFID 按钮
        btnRfidScan = findViewById(R.id.btn_rfid_scan);
        btnRfidStop = findViewById(R.id.btn_rfid_stop);
        btnRfidScan.setOnClickListener(v -> startRfidScan());
        btnRfidStop.setOnClickListener(v -> stopScan());

        // 条码按钮
        btnBarcodeScan = findViewById(R.id.btn_barcode_scan);
        btnBarcodeStop = findViewById(R.id.btn_barcode_stop);
        btnBarcodeScan.setOnClickListener(v -> startBarcodeScan());
        btnBarcodeStop.setOnClickListener(v -> stopScan());

        btnComplete.setOnClickListener(v ->
                RepositoryProvider.get().commitOutbound(String.valueOf(dbId),
                        new WmsCallback<OutboundOrder>() {
                            @Override public void onSuccess(OutboundOrder o) {
                                Toast.makeText(OutboundDetailActivity.this, R.string.msg_submitted, Toast.LENGTH_SHORT).show();
                                finish();
                            }
                            @Override public void onError(int code, String msg) {
                                Toast.makeText(OutboundDetailActivity.this, "提交失败: " + msg, Toast.LENGTH_SHORT).show();
                            }
                        }));
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
        // 如果条码正在扫码,先停掉
        if (activeScanType == ScanType.BARCODE) {
            stopScan();
        }
        activeScanType = ScanType.RFID_EPC;
        updateButtonStates();

        ScanManager.get(this).startScan(ScanType.RFID_EPC, new ScannerCallback() {
            @Override
            public void onScanResult(ScanResult result) {
                String code = result.getCode();
                if (code == null || code.isEmpty()) return;
                if (scannedCodes.contains(code)) {
                    Toast.makeText(OutboundDetailActivity.this,
                            "该码已扫描过: " + code, Toast.LENGTH_SHORT).show();
                    return;
                }
                submitScan(code);
            }

            @Override
            public void onError(int code, String msg) {
                stopScan();
                if (code == -100) {
                    Toast.makeText(OutboundDetailActivity.this, msg, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(OutboundDetailActivity.this,
                            "扫码异常: " + msg, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onTimeout() {
                stopScan();
                Toast.makeText(OutboundDetailActivity.this,
                        "RFID 扫码超时,未读到标签", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ============================================================
    // 条码扫码
    // ============================================================

    private void startBarcodeScan() {
        // 如果 RFID 正在扫码,先停掉
        if (activeScanType == ScanType.RFID_EPC) {
            stopScan();
        }
        activeScanType = ScanType.BARCODE;
        updateButtonStates();

        ScanManager.get(this).startScan(ScanType.BARCODE, new ScannerCallback() {
            @Override
            public void onScanResult(ScanResult result) {
                String code = result.getCode();
                if (code == null || code.isEmpty()) return;
                if (scannedCodes.contains(code)) {
                    Toast.makeText(OutboundDetailActivity.this,
                            "该码已扫描过: " + code, Toast.LENGTH_SHORT).show();
                    return;
                }
                submitScan(code);
            }

            @Override
            public void onError(int code, String msg) {
                stopScan();
                if (code == -100) {
                    Toast.makeText(OutboundDetailActivity.this, msg, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(OutboundDetailActivity.this,
                            "扫码异常: " + msg, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onTimeout() {
                stopScan();
                Toast.makeText(OutboundDetailActivity.this,
                        "条码扫码超时,未读到码", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ============================================================
    // 停止扫码
    // ============================================================

    private void stopScan() {
        ScanManager.get(this).stopScan();
        activeScanType = null;
        updateButtonStates();
    }

    /**
     * 更新按钮状态:
     *   未扫码 → 两个"开始"按钮可见,"停止"按钮隐藏
     *   RFID 扫码中 → RFID 停止按钮可见,RFID 开始隐藏;条码两个按钮都禁用
     *   条码扫码中 → 条码停止按钮可见,条码开始隐藏;RFID 两个按钮都禁用
     */
    private void updateButtonStates() {
        int colorPrimary = ContextCompat.getColor(this, R.color.primary);
        int colorOnPrimary = ContextCompat.getColor(this, R.color.on_primary);
        int colorDisabled = ContextCompat.getColor(this, R.color.text_hint);
        int colorDisabledBg = ContextCompat.getColor(this, R.color.divider);

        // 禁用状态: 灰色文字 + 浅灰背景
        ColorStateList disabledBg = ColorStateList.valueOf(colorDisabledBg);
        // 启用状态: 白色文字 + 主色背景
        ColorStateList enabledBg = ColorStateList.valueOf(colorPrimary);

        if (activeScanType == null) {
            // 空闲状态: 两个开始按钮都可点击
            btnRfidScan.setVisibility(View.VISIBLE);
            btnRfidScan.setEnabled(true);
            btnRfidScan.setClickable(true);
            btnRfidScan.setTextColor(colorOnPrimary);
            btnRfidScan.setBackgroundTintList(enabledBg);
            btnRfidStop.setVisibility(View.GONE);

            btnBarcodeScan.setVisibility(View.VISIBLE);
            btnBarcodeScan.setEnabled(true);
            btnBarcodeScan.setClickable(true);
            btnBarcodeScan.setTextColor(colorOnPrimary);
            btnBarcodeScan.setBackgroundTintList(enabledBg);
            btnBarcodeStop.setVisibility(View.GONE);
        } else if (activeScanType == ScanType.RFID_EPC) {
            // RFID 扫码中: RFID 显示停止按钮,条码按钮禁用
            btnRfidScan.setVisibility(View.GONE);
            btnRfidStop.setVisibility(View.VISIBLE);

            btnBarcodeScan.setEnabled(false);
            btnBarcodeScan.setClickable(false);
            btnBarcodeScan.setTextColor(colorDisabled);
            btnBarcodeScan.setBackgroundTintList(disabledBg);
            btnBarcodeScan.setVisibility(View.VISIBLE);
            btnBarcodeStop.setVisibility(View.GONE);
        } else {
            // 条码扫码中: 条码显示停止按钮,RFID 按钮禁用
            btnBarcodeScan.setVisibility(View.GONE);
            btnBarcodeStop.setVisibility(View.VISIBLE);

            btnRfidScan.setEnabled(false);
            btnRfidScan.setClickable(false);
            btnRfidScan.setTextColor(colorDisabled);
            btnRfidScan.setBackgroundTintList(disabledBg);
            btnRfidScan.setVisibility(View.VISIBLE);
            btnRfidStop.setVisibility(View.GONE);
        }
    }

    // ============================================================
    // 提交扫码结果
    // ============================================================

    private void submitScan(String code) {
        RepositoryProvider.get().submitOutboundScan(String.valueOf(dbId), code,
                new WmsCallback<OutboundOrder>() {
                    @Override public void onSuccess(OutboundOrder o) {
                        scannedCodes.add(code);
                        BeepUtil.success();
                        Toast.makeText(OutboundDetailActivity.this,
                                "已出库: " + code, Toast.LENGTH_SHORT).show();
                        render();
                    }
                    @Override public void onError(int code, String msg) {
                        handleScanError(code, msg);
                    }
                });
    }

    private void handleScanError(int code, String msg) {
        BeepUtil.error();
        if (msg == null) msg = "";
        if (msg.contains("码不存在")) {
            Toast.makeText(this, "该码未入库,已跳过", Toast.LENGTH_SHORT).show();
        } else if (msg.contains("已出库")) {
            Toast.makeText(this, "该码已出过库,已跳过", Toast.LENGTH_SHORT).show();
        } else if (msg.contains("已报废")) {
            Toast.makeText(this, "该码已报废,已跳过", Toast.LENGTH_SHORT).show();
        } else if (msg.contains("未锁定给本出库单")) {
            Toast.makeText(this, "该码未锁定给本出库单,已跳过", Toast.LENGTH_SHORT).show();
        } else if (msg.contains("已被其他出库单锁定")) {
            Toast.makeText(this, "该码已被其他出库单锁定,已跳过", Toast.LENGTH_SHORT).show();
        } else if (code == 404) {
            Toast.makeText(this, "该码不在当前出库单中,已跳过", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "扫码失败: " + msg, Toast.LENGTH_SHORT).show();
        }
    }

    // ============================================================
    // 渲染
    // ============================================================

    private void render() {
        if (dbId == null || dbId < 0) { finish(); return; }
        RepositoryProvider.get().getOutboundOrder(String.valueOf(dbId),
                new WmsCallback<OutboundOrder>() {
                    @Override public void onSuccess(OutboundOrder o) {
                        if (o == null) { finish(); return; }
                        doRender(o);
                    }
                    @Override public void onError(int code, String msg) {
                        Toast.makeText(OutboundDetailActivity.this, "加载失败: " + msg, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void doRender(OutboundOrder o) {
        ((TextView) findViewById(R.id.tv_id)).setText(o.id);
        StatusPill.apply((TextView) findViewById(R.id.tv_status), o.status);

        TextView tvCustomer = findViewById(R.id.tv_customer);
        tvCustomer.setText(o.customer == null || o.customer.isEmpty()
                ? "客户/部门: —" : "客户/部门: " + o.customer);

        TextView tvExternalNo = findViewById(R.id.tv_external_no);
        tvExternalNo.setText(o.externalNo == null || o.externalNo.isEmpty()
                ? "外部单号: —" : "外部单号: " + o.externalNo);

        TextView tvOperator = findViewById(R.id.tv_operator);
        tvOperator.setText(o.operator == null ? "操作员: —" : "操作员: " + o.operator);

        TextView tvRemark = findViewById(R.id.tv_remark);
        tvRemark.setText(o.remark == null || o.remark.isEmpty()
                ? getString(R.string.remark_empty) : getString(R.string.remark_prefix_fmt, o.remark));

        TextView tvSummary = findViewById(R.id.tv_summary);
        int itemCount = o.itemCount == null ? 0 : o.itemCount;
        int totalQtyAll  = o.totalQuantity == null ? 0 : o.totalQuantity;
        tvSummary.setText(itemCount + " 行 · " + totalQtyAll + " 件 · 创建于 " +
                (o.createdAt == null ? "—" : (o.createdAt.length() > 16 ? o.createdAt.substring(0, 16).replace("T", " ") : o.createdAt)));

        // 计算总数量
        totalQty = 0;
        if (o.lines != null) {
            for (OutboundOrder.Line l : o.lines) {
                totalQty += l.qty;
            }
        }
        scannedQty = scannedCodes.size();
        updateProgress();

        llLines.removeAllViews();
        LayoutInflater li = LayoutInflater.from(this);
        int colorScanned = ContextCompat.getColor(this, R.color.success);
        int colorUnscanned = ContextCompat.getColor(this, R.color.text_hint);
        if (o.lines == null || o.lines.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.lines_empty_outbound);
            empty.setTextColor(0xFF9AA3B2);
            empty.setPadding(24, 24, 24, 24);
            llLines.addView(empty);
        } else {
            for (OutboundOrder.Line l : o.lines) {
                View row = li.inflate(R.layout.item_inbound_line, llLines, false);
                TextView tvSkuName = row.findViewById(R.id.tv_sku_name);
                TextView tvInfo    = row.findViewById(R.id.tv_barcode);
                TextView tvQty     = row.findViewById(R.id.tv_qty);

                tvSkuName.setText((l.sku == null ? "?" : l.sku) + "  " + (l.name == null ? "" : l.name));
                tvQty.setText("× " + l.qty + " " + (l.uom == null ? "" : l.uom));

                StringBuilder info = new StringBuilder();
                if (l.location != null && !l.location.isEmpty()) info.append("库位: ").append(l.location).append("  ");
                if (l.unitPrice != null && !l.unitPrice.isEmpty()) info.append("单价: ¥").append(l.unitPrice).append("  ");
                if (l.rowRemark != null && !l.rowRemark.isEmpty()) info.append("备注: ").append(l.rowRemark);
                tvInfo.setText(info.toString());

                // 逐件显示库存单元编码
                LinearLayout container = new LinearLayout(this);
                container.setOrientation(LinearLayout.VERTICAL);
                container.setPadding(48, 8, 24, 8);
                if (l.codes != null && !l.codes.isEmpty()) {
                    for (String code : l.codes) {
                        TextView tvCode = new TextView(this);
                        boolean scanned = scannedCodes.contains(code);
                        tvCode.setText((scanned ? "✓ " : "  ") + code);
                        tvCode.setTextColor(scanned ? colorScanned : colorUnscanned);
                        tvCode.setTextSize(12);
                        tvCode.setPadding(0, 4, 0, 4);
                        container.addView(tvCode);
                    }
                }
                llLines.addView(row);
                if (container.getChildCount() > 0) {
                    llLines.addView(container);
                }
            }
        }

        // 显示已扫码记录
        llScanned.removeAllViews();
        if (scannedCodes.isEmpty()) {
            tvScannedTitle.setVisibility(View.GONE);
            llScanned.setVisibility(View.GONE);
        } else {
            tvScannedTitle.setVisibility(View.VISIBLE);
            llScanned.setVisibility(View.VISIBLE);
            for (String code : scannedCodes) {
                TextView tv = new TextView(this);
                tv.setText("✓ " + code);
                tv.setTextColor(ContextCompat.getColor(this, R.color.success));
                tv.setTextSize(13);
                tv.setPadding(24, 8, 24, 8);
                llScanned.addView(tv);
            }
        }

        boolean done = o.status == Enums.DocStatus.DONE || o.status == Enums.DocStatus.CANCELLED;
        btnComplete.setEnabled(!done);
        if (done) stopScan();
    }

    private void updateProgress() {
        if (totalQty <= 0) {
            tvProgress.setText("暂无明细");
            progressBar.setProgress(0);
            return;
        }
        tvProgress.setText("已扫描 " + scannedQty + " / " + totalQty + " 件");
        int percent = totalQty > 0 ? (scannedQty * 100 / totalQty) : 0;
        progressBar.setProgress(percent);
    }
}
