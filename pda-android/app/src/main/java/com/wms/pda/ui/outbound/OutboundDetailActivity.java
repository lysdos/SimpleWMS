package com.wms.pda.ui.outbound;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.wms.pda.data.model.EpcScanResult;
import com.wms.pda.data.model.Enums;
import com.wms.pda.data.model.Location;
import com.wms.pda.data.model.OutboundOrder;
import com.wms.pda.data.remote.BackendMessages;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;
import com.wms.pda.scan.ScanManager;
import com.wms.pda.scan.ScanResult;
import com.wms.pda.scan.ScanType;
import com.wms.pda.scan.ScannerCallback;
import com.wms.pda.ui.common.StatusPill;
import com.wms.pda.util.BeepUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private OutboundOrder currentOrder;  // 最近一次渲染的单据(编辑/取消用)
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

    /** 本单已提交过的码(成功或失败都算):连续扫码时失败的码静默跳过,不重复弹错误提示 */
    private final Set<String> triedCodes = new HashSet<>();

    /** 当前出库单的总数量(用于进度计算) */
    private int totalQty = 0;
    private int scannedQty = 0;

    /** 最近一次加载的明细行(扫码结果归一化用) */
    private List<OutboundOrder.Line> loadedLines = new ArrayList<>();

    /** 当前活跃的扫码类型(null = 未在扫码) */
    private ScanType activeScanType = null;

    /** 库位码 → 仓库名(展示用)。一个商品会分散在多个库位,光看库位码不知道在哪个仓。 */
    private final Map<String, String> warehouseByLoc = new HashMap<>();

    /** 单据已完成/已取消,扫码入口整体锁定 */
    private boolean scanLocked = false;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    /** 错误提示限频:2 秒内只响铃/弹一次,其余计数合并 */
    private long lastErrFeedbackMs = 0L;
    private int suppressedErrCount = 0;
    /** render 节流:连续扫码成功时合并刷新 */
    private Runnable pendingRender = null;

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

        findViewById(R.id.btn_clear_scans).setOnClickListener(v -> confirmClearScans());

        findViewById(R.id.btn_header_edit).setOnClickListener(v -> {
            if (currentOrder != null) EditOutboundHeaderDialog.show(this, currentOrder, this::render);
        });
        findViewById(R.id.btn_header_cancel).setOnClickListener(v -> {
            if (currentOrder != null) confirmCancelOrder();
        });

        findViewById(R.id.btn_add_item).setOnClickListener(v -> {
            if (currentOrder == null || scanLocked) return;
            AddOutboundItemDialog.show(this, dbId, this::render);
        });

        btnComplete.setOnClickListener(v ->
                RepositoryProvider.get().commitOutbound(String.valueOf(dbId),
                        new WmsCallback<OutboundOrder>() {
                            @Override public void onSuccess(OutboundOrder o) {
                                Toast.makeText(OutboundDetailActivity.this, R.string.msg_submitted, Toast.LENGTH_SHORT).show();
                                finish();
                            }
                            @Override public void onError(int code, String msg) {
                                Toast.makeText(OutboundDetailActivity.this,
                                        getString(R.string.msg_submit_failed, msg), Toast.LENGTH_SHORT).show();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
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
                String canon = canonicalCode(code);
                if (scannedCodes.contains(canon) || triedCodes.contains(canon)) return;
                submitEpcScan(code, canon);
            }

            @Override
            public void onError(int code, String msg) {
                stopScan();
                if (code == -100) {
                    Toast.makeText(OutboundDetailActivity.this, msg, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(OutboundDetailActivity.this,
                            getString(R.string.msg_scan_exception, msg), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onTimeout() {
                stopScan();
                Toast.makeText(OutboundDetailActivity.this,
                        getString(R.string.scan_rfid_timeout), Toast.LENGTH_SHORT).show();
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
                String canon = canonicalCode(code);
                if (scannedCodes.contains(canon) || triedCodes.contains(canon)) return;
                submitScan(code);
            }

            @Override
            public void onError(int code, String msg) {
                stopScan();
                if (code == -100) {
                    Toast.makeText(OutboundDetailActivity.this, msg, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(OutboundDetailActivity.this,
                            getString(R.string.msg_scan_exception, msg), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onTimeout() {
                stopScan();
                Toast.makeText(OutboundDetailActivity.this,
                        getString(R.string.scan_barcode_timeout), Toast.LENGTH_SHORT).show();
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

        if (scanLocked) {
            // 单据已完成/已取消: 两个扫码按钮全部禁用
            btnRfidScan.setVisibility(View.VISIBLE);
            btnRfidStop.setVisibility(View.GONE);
            btnRfidScan.setEnabled(false);
            btnRfidScan.setClickable(false);
            btnRfidScan.setTextColor(colorDisabled);
            btnRfidScan.setBackgroundTintList(disabledBg);

            btnBarcodeScan.setVisibility(View.VISIBLE);
            btnBarcodeStop.setVisibility(View.GONE);
            btnBarcodeScan.setEnabled(false);
            btnBarcodeScan.setClickable(false);
            btnBarcodeScan.setTextColor(colorDisabled);
            btnBarcodeScan.setBackgroundTintList(disabledBg);
            return;
        }

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

    /**
     * UHF EPC 扫码提交:走 /scan-epc 专用接口。
     * matched=true → 成功(已扫过幂等返回不发成功音,只刷新);
     * matched=false → 失败原因提示(限频),该码本次会话内静默跳过。
     */
    private void submitEpcScan(String epc, String canonical) {
        triedCodes.add(canonical);
        RepositoryProvider.get().submitOutboundEpcScan(String.valueOf(dbId), epc,
                new WmsCallback<EpcScanResult>() {
                    @Override public void onSuccess(EpcScanResult r) {
                        if (r != null && r.matched) {
                            scannedCodes.add(canonical);
                            if (r.alreadyScanned) {
                                Toast.makeText(OutboundDetailActivity.this,
                                        getString(R.string.scan_already_scanned_fmt, canonical), Toast.LENGTH_SHORT).show();
                            } else {
                                BeepUtil.success();
                                Toast.makeText(OutboundDetailActivity.this,
                                        getString(R.string.scanned_out_fmt, canonical), Toast.LENGTH_SHORT).show();
                            }
                            renderSoon();
                        } else {
                            // r.reason 来自后端,只翻包装、不翻它
                            String reason = (r == null || r.reason == null)
                                    ? getString(R.string.scan_unknown_reason) : r.reason;
                            showScanErrorLimited(reason);
                        }
                    }
                    @Override public void onError(int code, String msg) {
                        showScanErrorLimited(getString(R.string.msg_scan_failed, msg));
                    }
                });
    }

    /** 清空本单扫码确认:确认后调 reset-scans,成功后刷新(已扫标记/进度归零,可重新扫) */
    private void confirmClearScans() {
        if (scanLocked) return;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.outbound_clear_scans)
                .setMessage(R.string.outbound_clear_scans_confirm)
                .setPositiveButton(R.string.action_clear, (d, w) ->
                        RepositoryProvider.get().resetOutboundScans(String.valueOf(dbId),
                                new WmsCallback<Integer>() {
                                    @Override public void onSuccess(Integer deleted) {
                                        scannedCodes.clear();
                                        triedCodes.clear();
                                        Toast.makeText(OutboundDetailActivity.this,
                                                getString(R.string.outbound_clear_scans_done,
                                                        deleted == null ? 0 : deleted),
                                                Toast.LENGTH_SHORT).show();
                                        render();
                                    }
                                    @Override public void onError(int code, String msg) {
                                        Toast.makeText(OutboundDetailActivity.this,
                                                getString(R.string.outbound_clear_scans_fail, msg),
                                                Toast.LENGTH_SHORT).show();
                                    }
                                }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 失败提示限频:2 秒窗口内只响铃+弹一次,窗口内的重复错误合并计数 */
    private void showScanErrorLimited(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastErrFeedbackMs < 2000L) {
            suppressedErrCount++;
            return;
        }
        lastErrFeedbackMs = now;
        int n = suppressedErrCount;
        suppressedErrCount = 0;
        BeepUtil.error();
        String text = n > 0
                ? getString(R.string.scan_skipped_dup_fmt, reason, n)
                : getString(R.string.scan_skipped_fmt, reason);
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    /** 连续扫码成功时合并刷新,300ms 防抖 */
    private void renderSoon() {
        if (pendingRender != null) uiHandler.removeCallbacks(pendingRender);
        pendingRender = this::render;
        uiHandler.postDelayed(pendingRender, 300);
    }

    private void submitScan(String code) {
        RepositoryProvider.get().submitOutboundScan(String.valueOf(dbId), code,
                new WmsCallback<OutboundOrder>() {
                    @Override public void onSuccess(OutboundOrder o) {
                        scannedCodes.add(canonicalCode(code));
                        BeepUtil.success();
                        Toast.makeText(OutboundDetailActivity.this,
                                getString(R.string.scanned_out_fmt, code), Toast.LENGTH_SHORT).show();
                        renderSoon();
                    }
                    @Override public void onError(int code, String msg) {
                        handleScanError(code, msg);
                    }
                });
    }

    /** 扫码被拒时按**后端提示语**分流(为什么只能认字、不能翻译,见 BackendMessages 的类注释)。 */
    private void handleScanError(int code, String msg) {
        BeepUtil.error();
        if (msg == null) msg = "";
        if (msg.contains(BackendMessages.CODE_NOT_FOUND)) {
            Toast.makeText(this, R.string.scan_code_not_in_stock, Toast.LENGTH_SHORT).show();
        } else if (msg.contains(BackendMessages.ALREADY_OUT)) {
            Toast.makeText(this, R.string.scan_already_out, Toast.LENGTH_SHORT).show();
        } else if (msg.contains(BackendMessages.SCRAPPED)) {
            Toast.makeText(this, R.string.scan_scrapped, Toast.LENGTH_SHORT).show();
        } else if (msg.contains(BackendMessages.NOT_LOCKED)) {
            Toast.makeText(this, R.string.scan_not_locked, Toast.LENGTH_SHORT).show();
        } else if (msg.contains(BackendMessages.LOCKED_BY_OTHER)) {
            Toast.makeText(this, R.string.scan_locked_by_other, Toast.LENGTH_SHORT).show();
        } else if (code == 404) {
            Toast.makeText(this, R.string.scan_not_in_this_order, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, getString(R.string.msg_scan_failed, msg), Toast.LENGTH_SHORT).show();
        }
    }

    // ============================================================
    // 渲染
    // ============================================================

    private void render() {
        if (dbId == null || dbId < 0) { finish(); return; }
        // 先备好"库位码 → 仓库名"再拉单据,首帧就能显示「仓库/库位」。
        // 拉不到也照常拉单据(退化成只显示库位码)—— 一个展示用的映射不该挡住单据加载。
        if (warehouseByLoc.isEmpty()) {
            RepositoryProvider.get().listLocations(new WmsCallback<List<Location>>() {
                @Override public void onSuccess(List<Location> locs) {
                    if (locs != null) {
                        for (Location l : locs) {
                            if (l.code != null) warehouseByLoc.put(l.code, l.warehouseName);
                        }
                    }
                    loadOrder();
                }
                @Override public void onError(int code, String msg) { loadOrder(); }
            });
        } else {
            loadOrder();
        }
    }

    private void loadOrder() {
        RepositoryProvider.get().getOutboundOrder(String.valueOf(dbId),
                new WmsCallback<OutboundOrder>() {
                    @Override public void onSuccess(OutboundOrder o) {
                        if (o == null) { finish(); return; }
                        doRender(o);
                    }
                    @Override public void onError(int code, String msg) {
                        Toast.makeText(OutboundDetailActivity.this,
                                getString(R.string.msg_load_failed, msg), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /**
     * 库位显示:带上级仓库(「主仓库/A-01-01」);查不到仓库就只显示库位码。
     * 空库位码统一说「未指定库位」—— 与 Web 出库详情、CSV 的「库位」列一个说法。
     */
    private String locLabel(String code) {
        if (code == null || code.isEmpty()) return getString(R.string.outbound_loc_unassigned);
        String wh = warehouseByLoc.get(code);
        return (wh == null || wh.isEmpty()) ? code : wh + "/" + code;
    }

    /**
     * 把「库位码 → 码列表」按仓库重新归组:仓库名 → (库位码 → 码列表)。
     *
     * 为什么要这一层:同一个商品可能主仓放 2 件、副仓放 1 件,只按库位码排序输出的话,
     * 主仓的两个库位会被副仓的库位劈开,还是看不出"哪个仓的码在一起"。
     * 仓库名靠 warehouseByLoc 反查(拉不到就归到"未分配仓库",不拦着显示)。
     */
    private Map<String, Map<String, List<String>>> groupCodesByWarehouse(
            Map<String, List<String>> codesByLocation) {
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : codesByLocation.entrySet()) {
            String loc = e.getKey();
            String wh;
            if (loc == null || loc.isEmpty()) {
                wh = getString(R.string.outbound_loc_unassigned);
            } else {
                String name = warehouseByLoc.get(loc);
                wh = (name == null || name.isEmpty()) ? getString(R.string.outbound_loc_no_warehouse) : name;
            }
            Map<String, List<String>> locs = out.get(wh);
            if (locs == null) {
                locs = new LinkedHashMap<>();
                out.put(wh, locs);
            }
            locs.put(loc, e.getValue() == null ? new ArrayList<>() : e.getValue());
        }
        return out;
    }

    private void doRender(OutboundOrder o) {
        currentOrder = o;
        loadedLines = o.lines == null ? new ArrayList<>() : o.lines;
        // 以服务端扫码记录为准(跨会话/重进页面标记不丢)
        scannedCodes.clear();
        if (o.scannedCodes != null) scannedCodes.addAll(o.scannedCodes);
        ((TextView) findViewById(R.id.tv_id)).setText(o.id);
        StatusPill.apply((TextView) findViewById(R.id.tv_status), o.status);

        TextView tvCustomer = findViewById(R.id.tv_customer);
        tvCustomer.setText(getString(R.string.customer_prefix_fmt,
                o.customer == null || o.customer.isEmpty() ? "—" : o.customer));

        TextView tvExternalNo = findViewById(R.id.tv_external_no);
        tvExternalNo.setText(getString(R.string.external_no_prefix_fmt,
                o.externalNo == null || o.externalNo.isEmpty() ? "—" : o.externalNo));

        TextView tvOperator = findViewById(R.id.tv_operator);
        tvOperator.setText(getString(R.string.created_by_fmt,
                o.createdBy == null ? "—" : o.createdBy));

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
                if (l.location != null && !l.location.isEmpty()) {
                    // 历史单:手工指定过库位
                    info.append(getString(R.string.location_label_fmt, locLabel(l.location))).append("  ");
                } else {
                    // 新单:出库是"自动跨库位找货",行上没有单一库位,靠下面的拣货分布定位
                    info.append(getString(R.string.location_label_fmt, getString(R.string.location_auto)))
                            .append("  ");
                }
                if (l.picks != null && !l.picks.isEmpty()) {
                    StringBuilder p = new StringBuilder();
                    for (Map.Entry<String, Integer> e : l.picks.entrySet()) {
                        if (p.length() > 0) p.append(" · ");
                        p.append(locLabel(e.getKey())).append(" × ").append(e.getValue());
                    }
                    info.append("拣货: ").append(p).append("  ");
                }
                if (l.unitPrice != null && !l.unitPrice.isEmpty()) info.append("单价: ¥").append(l.unitPrice).append("  ");
                if (l.rowRemark != null && !l.rowRemark.isEmpty()) info.append("备注: ").append(l.rowRemark);
                tvInfo.setText(info.toString());

                // 逐件显示库存单元编码
                LinearLayout container = new LinearLayout(this);
                container.setOrientation(LinearLayout.VERTICAL);
                container.setPadding(48, 8, 24, 8);
                // 码按**仓库**分组(一个商品可能主仓 2 件、副仓 1 件,平铺在一起看不出
                // 哪个码该放哪个仓)。分组只改渲染,✓ 比对仍按原始码 —— 别在这里动手脚,
                // 否则 canonicalCode 那套规范化会对不上、标记漏打。
                Map<String, List<String>> codesByLoc = l.codesByLocation;
                if ((codesByLoc == null || codesByLoc.isEmpty()) && l.codes != null && !l.codes.isEmpty()) {
                    // 后端还没升级(没有 codesByLocation)时的兜底:退回不分组
                    codesByLoc = new LinkedHashMap<>();
                    codesByLoc.put(null, l.codes);
                }
                if (codesByLoc != null && !codesByLoc.isEmpty()) {
                    for (Map.Entry<String, Map<String, List<String>>> wh : groupCodesByWarehouse(codesByLoc).entrySet()) {
                        TextView tvWh = new TextView(this);
                        tvWh.setText(wh.getKey());
                        tvWh.setTextSize(12);
                        tvWh.setTypeface(null, Typeface.BOLD);
                        tvWh.setTextColor(0xFF6B7280);
                        tvWh.setPadding(0, 8, 0, 2);
                        container.addView(tvWh);
                        for (Map.Entry<String, List<String>> le : wh.getValue().entrySet()) {
                            if (le.getKey() != null && !le.getKey().isEmpty()) {
                                TextView tvLoc = new TextView(this);
                                tvLoc.setText(le.getKey());
                                tvLoc.setTextSize(11);
                                tvLoc.setTextColor(0xFF9AA3B2);
                                tvLoc.setPadding(0, 2, 0, 0);
                                container.addView(tvLoc);
                            }
                            for (String code : le.getValue()) {
                                TextView tvCode = new TextView(this);
                                boolean scanned = scannedCodes.contains(code);
                                tvCode.setText((scanned ? "✓ " : "  ") + code);
                                tvCode.setTextColor(scanned ? colorScanned : colorUnscanned);
                                tvCode.setTextSize(12);
                                tvCode.setPadding(0, 4, 0, 4);
                                container.addView(tvCode);
                            }
                        }
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
        scanLocked = done;
        btnComplete.setEnabled(!done);
        findViewById(R.id.btn_clear_scans).setEnabled(!done);
        findViewById(R.id.btn_clear_scans).setAlpha(done ? 0.4f : 1f);
        findViewById(R.id.ll_header_actions).setVisibility(o.status == Enums.DocStatus.DRAFT ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_add_item).setVisibility(o.status == Enums.DocStatus.DRAFT ? View.VISIBLE : View.GONE);
        if (done) stopScan();
        updateButtonStates();
    }

    private void confirmCancelOrder() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(R.string.confirm_cancel_outbound)
                .setPositiveButton(R.string.action_cancel_order, (d, w) -> {
                    RepositoryProvider.get().cancelOutbound(String.valueOf(dbId),
                            new WmsCallback<Void>() {
                                @Override public void onSuccess(Void v) {
                                    Toast.makeText(OutboundDetailActivity.this, R.string.msg_order_cancelled, Toast.LENGTH_SHORT).show();
                                    finish();  // 取消后单据不再是草稿,返回列表
                                }
                                @Override public void onError(int code, String msg) {
                                    Toast.makeText(OutboundDetailActivity.this,
                                            getString(R.string.msg_cancel_failed, msg), Toast.LENGTH_SHORT).show();
                                }
                            });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * 把扫到的原始码归一化成后端存的完整编码。
     * 条码枪可能吞前导零(后端 findByCode 靠尾匹配兜底),
     * 若直接把原始码放进 scannedCodes,明细区的 ✓ 标记比对不上会漏打。
     */
    private String canonicalCode(String raw) {
        if (raw == null) return null;
        String r = raw.trim();
        for (OutboundOrder.Line l : loadedLines) {
            if (l.codes == null) continue;
            for (String c : l.codes) {
                if (c == null) continue;
                if (c.equals(r) || c.endsWith(r) || r.endsWith(c)) return c;
            }
        }
        return r;
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
