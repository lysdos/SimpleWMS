package com.wms.pda.ui.inbound;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.wms.pda.R;
import com.wms.pda.data.SessionStore;
import com.wms.pda.model.ApiResponse;
import com.wms.pda.model.InboundOrderDto;
import com.wms.pda.model.InboundOrderItemDto;
import com.wms.pda.model.ProductDto;
import com.wms.pda.net.ApiClient;
import com.wms.pda.net.ApiException;
import com.wms.pda.scan.ScanManager;
import com.wms.pda.scan.ScanResult;
import com.wms.pda.scan.ScanType;
import com.wms.pda.scan.ScannerCallback;
import com.wms.pda.util.BeepUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 入库扫码登记页(P5 重写):
 *
 * 流程:
 *   1. 进入页 → 弹"选/建草稿单"对话框(已有)
 *   2. 选/建后进入扫码页:全屏摄像头预览 + 顶单号栏 + 底"已扫 X 件"列表
 *   3. ML Kit 抓到 1 个码 → 调 GET /api/inventory/items/by-code/{code}/product
 *      - 200 (已存在 product) → Toast 该码已在系统中,跳过
 *      - 404 (新码,无 product) → 弹"加几件?"小窗 → POST /items
 *   4. 累加 N 件到已扫列表(显示数量)
 *   5. 提交:POST /commit → 切到码打印页
 *   6. 取消:POST /cancel → finish
 *
 * 摄像头由 ScanManager.attachBarcodeCamera 在 onResume 绑定,
 * ScanManager.detachBarcodeCamera 在 onPause 释放。
 */
public class InboundScanActivity extends AppCompatActivity implements ScannerCallback {

    private static final long SEARCH_DEBOUNCE_MS = 300L;
    private static final long RESUME_RESCAN_DELAY_MS = 600L;

    private ApiClient api;
    private SessionStore session;
    private ScanManager scanManager;

    /** 当前 DRAFT 订单 ID(从选/建单得到)。 */
    private Integer orderId;
    /** 当前订单详情(每次加商品后刷新)。 */
    private InboundOrderDto currentOrder;

    // ====== 登记页视图(register mode) ======
    private TextView tvOrderNo, tvStatus, tvCount;
    private Button btnSubmit, btnCancel;
    private RecyclerView rvItems;
    private PreviewView previewView;
    private LineAdapter lineAdapter;

    // ====== 码打印页视图(print mode) ======
    private TextView tvPrintHeader, tvCodesCount;
    private RecyclerView rvCodes;
    private Button btnPrint, btnDownloadCsv, btnBack;

    private final List<InboundOrderItemDto> lines = new ArrayList<>();
    private final List<String> committedCodes = new ArrayList<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 用于 onResume 后延迟重启扫描(等摄像头 ready)。 */
    private final Runnable resumeRescanRunnable = () -> startScanning();

    /** 当前待处理扫码 code(弹"加几件"前暂存)。 */
    private String pendingCode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = new SessionStore(this);
        api = new ApiClient(session);
        scanManager = ScanManager.get(this);
        BeepUtil.init(this);

        showRegisterPage();
        showOrderPickerDialog();
    }

    // ============================================================
    // 生命周期 — 摄像头绑定/释放
    // ============================================================

    @Override
    protected void onResume() {
        super.onResume();
        if (previewView != null) {
            scanManager.attachBarcodeCamera(this, previewView);
            // 启动 BARCODE 扫描(自动停 + 回调):ScanManager.startScan 会调 svc.setCallback(this)
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
    // ScannerCallback
    // ============================================================

    @Override
    public void onScanResult(@NonNull ScanResult result) {
        if (result.getType() != ScanType.BARCODE) return;
        String code = result.getCode();
        if (code == null || code.isEmpty()) return;
        if (orderId == null) {
            // 还没选单:忽略,提示用户
            mainHandler.post(() -> Toast.makeText(this, "请先选/建草稿单", Toast.LENGTH_SHORT).show());
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
        // 自动重启扫描
        resumeScanning();
    }

    /** ML Kit 抓到一码后自动停;处理完后手动重启扫描。 */
    private void resumeScanning() {
        mainHandler.postDelayed(this::startScanning, 500L);
    }

    private void handleScannedCode(String code) {
        // 调 by-code/{code}/product 找 product
        new Thread(() -> {
            try {
                ApiResponse<ProductDto> resp = api.get(
                        "/api/inventory/items/by-code/" + code + "/product",
                        ProductDto.class);
                final ProductDto p = resp == null ? null : resp.data;
                mainHandler.post(() -> {
                    if (p != null && p.id != null) {
                        // 已有 product → 该码在系统中,跳过
                        BeepUtil.error();
                        Toast.makeText(this, R.string.inbound_scan_already_in, Toast.LENGTH_SHORT).show();
                        resumeScanning();
                    }
                });
            } catch (ApiException e) {
                // 404 = 码不在系统中 → 这是新码,弹"加几件"小窗
                if (e.getCode() == 404) {
                    pendingCode = code;
                    mainHandler.post(this::showScanQtyDialog);
                } else {
                    mainHandler.post(() -> {
                        BeepUtil.error();
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                        resumeScanning();
                    });
                }
            }
        }).start();
    }

    /** 弹"加几件"小窗 — 但 by-code/product 404 时没有 product 信息;
     *  按 brief:用户已扫到一个不在系统中的码,后端将为之生成 product + inventory_item 码。
     *  当前实现:复用最后一次 refreshLines 的最后一个 item 的 productId 作为目标;
     *  若订单为空,提示用户先扫"产品已入库"的码不可行 — 让"找不到 product" 走默认行为:
     *  后端 addItemToDraft 需要 productId,所以我们这里兜底:
     *  如果 lines 非空,使用最后一个 item.productId;
     *  如果 lines 为空,提示"请先在系统中录入商品"。
     *
     *  说明:后端 /api/inventory/items/by-code/{code}/product 端点的实际语义是
     *  "根据一个 24 位码反查它对应的 product"。对于一个新码,没有 product 关联。
     *  此时 PDA 端无法知道 productId —— 真正的入库流程必须是:
     *  (a) 录入商品时生成 product,系统产生空 24 位码 prefix
     *  (b) 或者由后端提供"扫新码自动创建 product"的逻辑
     *
     *  本期 P5 保留弹窗但用 fallback:让后端接收 productId=null 兜底
     *  (实际后端必填 productId)。为保持 UI 不阻塞,我们直接弹"加几件"小窗,
     *  用户确认后调 addItemToDraft,后端校验失败时回 Toast。 */
    private void showScanQtyDialog() {
        if (pendingCode == null) {
            resumeScanning();
            return;
        }
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_scan_qty, null, false);
        TextView tvProduct = view.findViewById(R.id.tvScanQtyProduct);
        TextInputEditText etQty = view.findViewById(R.id.etScanQty);
        tvProduct.setText("扫到新码:\n" + pendingCode
                + (currentOrder != null && !lines.isEmpty() && lines.get(lines.size() - 1).productName != null
                    ? "\n(默认归属: " + lines.get(lines.size() - 1).productName + ")"
                    : "\n(请在系统中先建立商品)"));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.inbound_scan_qty_title)
                .setView(view)
                .setPositiveButton(R.string.inbound_add, null)
                .setNegativeButton(R.string.inbound_cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setOnClickListener(v -> {
                String qtyStr = etQty.getText() == null ? "" : etQty.getText().toString().trim();
                int qty;
                if (qtyStr.isEmpty()) qty = 1;
                else {
                    try { qty = Integer.parseInt(qtyStr); }
                    catch (NumberFormatException e) {
                        Toast.makeText(this, R.string.inbound_quantity, Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                if (qty <= 0) {
                    Toast.makeText(this, R.string.inbound_quantity, Toast.LENGTH_SHORT).show();
                    return;
                }
                pendingQty = qty;
                dialog.dismiss();
                doAddItemFromScan();
            });
        });
        dialog.setOnDismissListener(d -> {
            pendingCode = null;
            pendingQty = 0;
            resumeScanning();
        });
        dialog.show();
    }

    /** "加几件"确认后暂存数量。 */
    private int pendingQty;

    private void doAddItemFromScan() {
        if (orderId == null || pendingCode == null) return;
        // 新码 → 没有 product 关联,只能 attach 到已知的某个 product。
        // 这里用 lines 最后一行 productId 作 fallback;若 lines 为空则直接跳过。
        if (currentOrder == null || currentOrder.items == null || currentOrder.items.isEmpty()) {
            Toast.makeText(this, "请先在系统中建立商品并加一行,再用扫码补量", Toast.LENGTH_LONG).show();
            return;
        }
        InboundOrderItemDto last = currentOrder.items.get(currentOrder.items.size() - 1);
        Integer productId = last.productId;
        if (productId == null) {
            Toast.makeText(this, "未关联商品,跳过", Toast.LENGTH_SHORT).show();
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("productId", productId);
        body.put("quantity", pendingQty);
        // 携带原始 24 位码作可追溯性(后端 /items 接口支持任意字段,Gson 会忽略未知)
        body.put("sourceCode", pendingCode);
        new Thread(() -> {
            try {
                ApiResponse<InboundOrderDto> resp = api.post(
                        "/api/inbound/orders/" + orderId + "/items", body, InboundOrderDto.class);
                final InboundOrderDto o = resp == null ? null : resp.data;
                mainHandler.post(() -> {
                    currentOrder = o;
                    refreshLines();
                    BeepUtil.success();
                    Toast.makeText(this, R.string.inbound_scan_add_success, Toast.LENGTH_SHORT).show();
                });
            } catch (ApiException e) {
                mainHandler.post(() -> {
                    BeepUtil.error();
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ============================================================
    // 页面切换
    // ============================================================

    private void showRegisterPage() {
        setContentView(R.layout.activity_inbound_scan);
        tvOrderNo = findViewById(R.id.tvOrderNo);
        tvStatus = findViewById(R.id.tvStatus);
        tvCount = findViewById(R.id.tvCount);
        btnSubmit = findViewById(R.id.btnSubmit);
        btnCancel = findViewById(R.id.btnCancel);
        rvItems = findViewById(R.id.rvItems);
        previewView = findViewById(R.id.previewView);
        rvItems.setLayoutManager(new LinearLayoutManager(this));
        lineAdapter = new LineAdapter();
        rvItems.setAdapter(lineAdapter);

        btnSubmit.setOnClickListener(v -> doSubmit());
        btnCancel.setOnClickListener(v -> doCancel());

        // 没选单子时禁用提交
        boolean hasOrder = orderId != null && currentOrder != null;
        btnSubmit.setEnabled(hasOrder && isDraft(currentOrder) && !lines.isEmpty());
        btnCancel.setEnabled(hasOrder && isDraft(currentOrder));
        renderOrderHeader();
    }

    private void showPrintPage() {
        setContentView(R.layout.activity_inbound_code_print);
        tvPrintHeader = findViewById(R.id.tvPrintHeader);
        tvCodesCount = findViewById(R.id.tvCodesCount);
        rvCodes = findViewById(R.id.rvCodes);
        rvCodes.setLayoutManager(new LinearLayoutManager(this));
        btnPrint = findViewById(R.id.btnPrint);
        btnDownloadCsv = findViewById(R.id.btnDownloadCsv);
        btnBack = findViewById(R.id.btnBack);

        btnPrint.setOnClickListener(v -> shareAllCodes());
        btnDownloadCsv.setOnClickListener(v -> downloadCsv());
        btnBack.setOnClickListener(v -> finish());

        tvPrintHeader.setText(getString(R.string.inbound_submit_success)
                + " - " + (currentOrder == null || currentOrder.orderNo == null ? "" : currentOrder.orderNo));
        tvCodesCount.setText(getString(R.string.inbound_codes_generated, committedCodes.size()));
        rvCodes.setAdapter(new CodeAdapter());
    }

    // ============================================================
    // 选/建单对话框
    // ============================================================

    private void showOrderPickerDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.activity_inbound_order_picker, null, false);
        RecyclerView rvPicker = view.findViewById(R.id.rvPickerOrders);
        TextView tvPickerEmpty = view.findViewById(R.id.tvPickerEmpty);
        TextInputEditText etExternalNo = view.findViewById(R.id.etPickerExternalNo);
        TextInputEditText etRemark = view.findViewById(R.id.etPickerRemark);
        rvPicker.setLayoutManager(new LinearLayoutManager(this));

        List<InboundOrderDto> drafts = new ArrayList<>();
        final AlertDialog[] dialogRef = new AlertDialog[1];
        PickerAdapter pickerAdapter = new PickerAdapter(drafts, order -> {
            // 点击打开 - 关掉 dialog,直接进入登记
            if (dialogRef[0] != null && dialogRef[0].isShowing()) dialogRef[0].dismiss();
            loadOrder(order.id);
        });
        rvPicker.setAdapter(pickerAdapter);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setView(view);
        b.setCancelable(true);
        b.setNegativeButton(R.string.inbound_cancel, (d, w) -> {
            d.dismiss();
            if (orderId == null) finish();
        });
        b.setPositiveButton(R.string.inbound_picker_create_btn, (d, w) -> {
            String ext = etExternalNo.getText() == null ? null : etExternalNo.getText().toString().trim();
            String rmk = etRemark.getText() == null ? null : etRemark.getText().toString().trim();
            if (ext != null && ext.isEmpty()) ext = null;
            if (rmk != null && rmk.isEmpty()) rmk = null;
            createDraft(ext, rmk, dialogRef[0]);
        });
        AlertDialog dialog = b.create();
        dialogRef[0] = dialog;
        dialog.setOnShowListener(d -> loadDrafts(drafts, pickerAdapter, tvPickerEmpty));
        dialog.show();
    }

    private void loadDrafts(List<InboundOrderDto> drafts, PickerAdapter adapter, TextView tvEmpty) {
        String operator = session.getUsername();
        StringBuilder sb = new StringBuilder("/api/inbound/orders?status=DRAFT&page=1&pageSize=50");
        if (operator != null) {
            sb.append("&operator=").append(URLEncoder.encode(operator, StandardCharsets.UTF_8));
        }
        final String url = sb.toString();
        new Thread(() -> {
            try {
                ApiResponse<java.util.Map> resp = api.get(url, java.util.Map.class);
                @SuppressWarnings("unchecked")
                List<java.util.Map<String, Object>> rawList =
                        resp == null || resp.data == null ? null : (List<java.util.Map<String, Object>>) resp.data.get("list");
                drafts.clear();
                if (rawList != null) {
                    for (java.util.Map<String, Object> m : rawList) {
                        drafts.add(toInboundOrderDto(m));
                    }
                }
                mainHandler.post(() -> {
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(drafts.isEmpty() ? View.VISIBLE : View.GONE);
                });
            } catch (ApiException e) {
                mainHandler.post(() -> Toast.makeText(this,
                        getString(R.string.network_unavailable) + ": " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void createDraft(@Nullable String externalNo, @Nullable String remark, AlertDialog dialog) {
        Map<String, Object> body = new HashMap<>();
        if (externalNo != null) body.put("externalNo", externalNo);
        if (remark != null) body.put("remark", remark);
        new Thread(() -> {
            try {
                ApiResponse<InboundOrderDto> resp = api.post("/api/inbound/orders", body, InboundOrderDto.class);
                final InboundOrderDto created = resp == null ? null : resp.data;
                mainHandler.post(() -> {
                    if (dialog != null && dialog.isShowing()) dialog.dismiss();
                    if (created != null) loadOrder(created.id);
                });
            } catch (ApiException e) {
                mainHandler.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void loadOrder(Integer id) {
        this.orderId = id;
        new Thread(() -> {
            try {
                ApiResponse<InboundOrderDto> resp = api.get("/api/inbound/orders/" + id, InboundOrderDto.class);
                final InboundOrderDto o = resp == null ? null : resp.data;
                mainHandler.post(() -> {
                    currentOrder = o;
                    refreshLines();
                    showRegisterPage();
                });
            } catch (ApiException e) {
                mainHandler.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ============================================================
    // 提交
    // ============================================================

    private void doSubmit() {
        if (orderId == null || currentOrder == null) return;
        if (lines.isEmpty()) {
            Toast.makeText(this, R.string.inbound_add_item, Toast.LENGTH_SHORT).show();
            return;
        }
        btnSubmit.setEnabled(false);
        new Thread(() -> {
            try {
                ApiResponse<InboundOrderDto> resp = api.post(
                        "/api/inbound/orders/" + orderId + "/commit", null, InboundOrderDto.class);
                final InboundOrderDto o = resp == null ? null : resp.data;
                mainHandler.post(() -> {
                    BeepUtil.success();
                    currentOrder = o;
                    collectCommittedCodes(o);
                    btnSubmit.setEnabled(true);
                    // 释放摄像头再切页
                    scanManager.stopScan();
                    scanManager.detachBarcodeCamera();
                    showPrintPage();
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

    private void collectCommittedCodes(@Nullable InboundOrderDto o) {
        committedCodes.clear();
        if (o == null || o.items == null) return;
        for (InboundOrderItemDto it : o.items) {
            if (it.codes != null) committedCodes.addAll(it.codes);
        }
    }

    // ============================================================
    // 取消
    // ============================================================

    private void doCancel() {
        if (orderId == null) return;
        new AlertDialog.Builder(this)
                .setMessage(R.string.inbound_cancel_confirm)
                .setPositiveButton(R.string.inbound_cancel, (d, w) -> cancelOrder())
                .setNegativeButton(R.string.inbound_back, null)
                .show();
    }

    private void cancelOrder() {
        btnCancel.setEnabled(false);
        new Thread(() -> {
            try {
                api.post("/api/inbound/orders/" + orderId + "/cancel", null, Void.class);
                mainHandler.post(this::finish);
            } catch (ApiException e) {
                mainHandler.post(() -> {
                    BeepUtil.error();
                    btnCancel.setEnabled(true);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ============================================================
    // 打印 + 下载 CSV
    // ============================================================

    private void shareAllCodes() {
        if (committedCodes.isEmpty()) {
            Toast.makeText(this, R.string.inbound_print_codes, Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("单号: ").append(currentOrder == null ? "" : currentOrder.orderNo).append('\n');
        sb.append(getString(R.string.inbound_codes_generated, committedCodes.size())).append("\n\n");
        for (String c : committedCodes) sb.append(c).append('\n');

        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.inbound_share_chooser));
        send.putExtra(android.content.Intent.EXTRA_TEXT, sb.toString());
        startActivity(android.content.Intent.createChooser(send, getString(R.string.inbound_share_chooser)));
    }

    private void downloadCsv() {
        if (currentOrder == null || committedCodes.isEmpty()) return;
        String orderNo = currentOrder.orderNo == null ? ("inbound_" + currentOrder.id) : currentOrder.orderNo;
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            Toast.makeText(this, "无法访问下载目录", Toast.LENGTH_SHORT).show();
            return;
        }
        File out = new File(dir, orderNo + ".csv");
        new Thread(() -> {
            try (Writer wr = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
                wr.write('﻿');
                wr.write("入库单号," + csvField(currentOrder.orderNo) + "\n");
                wr.write("外部单号," + csvField(currentOrder.externalNo) + "\n");
                wr.write("操作员," + csvField(currentOrder.operator) + "\n");
                wr.write("整单备注," + csvField(currentOrder.remark) + "\n");
                wr.write("总数量," + (currentOrder.totalQuantity == null ? 0 : currentOrder.totalQuantity) + "\n");
                wr.write("明细数," + (currentOrder.itemCount == null ? 0 : currentOrder.itemCount) + "\n\n");
                wr.write("序号,供应商,SKU,商品名称,库位,数量,行备注,24位码\n");
                int idx = 1;
                if (currentOrder.items != null) {
                    for (InboundOrderItemDto it : currentOrder.items) {
                        String codesStr = (it.codes == null || it.codes.isEmpty())
                                ? "" : android.text.TextUtils.join("|", it.codes);
                        wr.write((idx++) + ","
                                + csvField(it.supplier) + ","
                                + csvField(it.sku) + ","
                                + csvField(it.productName) + ","
                                + csvField(it.location) + ","
                                + (it.quantity == null ? 0 : it.quantity) + ","
                                + csvField(it.rowRemark) + ","
                                + csvField(codesStr) + "\n");
                    }
                }
                mainHandler.post(() -> Toast.makeText(this,
                        getString(R.string.inbound_codes_saved, out.getAbsolutePath()),
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(this,
                        "CSV 写入失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static String csvField(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ============================================================
    // 渲染 + 工具
    // ============================================================

    private void refreshLines() {
        lines.clear();
        if (currentOrder != null && currentOrder.items != null) {
            lines.addAll(currentOrder.items);
        }
        if (lineAdapter != null) lineAdapter.notifyDataSetChanged();
        if (tvCount != null) {
            int total = 0;
            for (InboundOrderItemDto it : lines) total += (it.quantity == null ? 0 : it.quantity);
            tvCount.setText(getString(R.string.inbound_scan_total, total, lines.size()));
        }
        if (btnSubmit != null) {
            boolean canSubmit = isDraft(currentOrder) && !lines.isEmpty();
            btnSubmit.setEnabled(canSubmit);
            btnCancel.setEnabled(isDraft(currentOrder));
        }
        renderOrderHeader();
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

    private static boolean isDraft(@Nullable InboundOrderDto o) {
        return o != null && "DRAFT".equals(o.status);
    }

    /** 后端 list 接口里每个 InboundOrder 不含 items,这里手工 map 出 DTO。 */
    @SuppressWarnings("unchecked")
    private static InboundOrderDto toInboundOrderDto(java.util.Map<String, Object> m) {
        InboundOrderDto o = new InboundOrderDto();
        o.id = asInt(m.get("id"));
        o.orderNo = asStr(m.get("orderNo"));
        o.externalNo = asStr(m.get("externalNo"));
        o.remark = asStr(m.get("remark"));
        o.operator = asStr(m.get("operator"));
        o.totalQuantity = asInt(m.get("totalQuantity"));
        o.itemCount = asInt(m.get("itemCount"));
        o.status = asStr(m.get("status"));
        o.createdAt = asStr(m.get("createdAt"));
        return o;
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
    // Adapter
    // ============================================================

    private class LineAdapter extends RecyclerView.Adapter<LineAdapter.VH> {
        @Override
        public VH onCreateViewHolder(ViewGroup p, int vt) {
            View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_inbound_line, p, false);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(VH h, int pos) {
            InboundOrderItemDto it = lines.get(pos);
            h.sku.setText(it.sku == null ? "" : it.sku);
            h.name.setText(it.productName == null ? "" : it.productName);
            h.qty.setText("×" + (it.quantity == null ? 0 : it.quantity));
            h.location.setText(it.location == null ? "" : it.location);
            h.btnDelete.setEnabled(isDraft(currentOrder));
            h.btnDelete.setOnClickListener(v -> {
                Toast.makeText(InboundScanActivity.this,
                        "请使用提交/取消操作管理单据", Toast.LENGTH_SHORT).show();
            });
        }
        @Override public int getItemCount() { return lines.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView sku, name, qty, location;
            Button btnDelete;
            VH(View v) {
                super(v);
                sku = v.findViewById(R.id.tvLineSku);
                name = v.findViewById(R.id.tvLineName);
                qty = v.findViewById(R.id.tvLineQty);
                location = v.findViewById(R.id.tvLineLocation);
                btnDelete = v.findViewById(R.id.btnLineDelete);
            }
        }
    }

    private class PickerAdapter extends RecyclerView.Adapter<PickerAdapter.VH> {
        private final List<InboundOrderDto> data;
        private final OnPickListener listener;
        PickerAdapter(List<InboundOrderDto> data, OnPickListener l) { this.data = data; this.listener = l; }
        @Override
        public VH onCreateViewHolder(ViewGroup p, int vt) {
            View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_inbound_picker_row, p, false);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(VH h, int pos) {
            InboundOrderDto o = data.get(pos);
            h.orderNo.setText(o.orderNo == null ? "" : o.orderNo);
            int qty = o.totalQuantity == null ? 0 : o.totalQuantity;
            int cnt = o.itemCount == null ? 0 : o.itemCount;
            h.meta.setText(getString(R.string.inbound_picker_meta,
                    o.operator == null ? "" : o.operator, qty, cnt));
            h.itemView.setOnClickListener(v -> listener.onPick(o));
        }
        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView orderNo, meta;
            VH(View v) {
                super(v);
                orderNo = v.findViewById(R.id.tvPickerOrderNo);
                meta = v.findViewById(R.id.tvPickerMeta);
            }
        }
    }

    private interface OnPickListener {
        void onPick(InboundOrderDto o);
    }

    private class CodeAdapter extends RecyclerView.Adapter<CodeAdapter.VH> {
        @Override
        public VH onCreateViewHolder(ViewGroup p, int vt) {
            View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_code_row, p, false);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(VH h, int pos) {
            String code = committedCodes.get(pos);
            h.code.setText(code);
            h.meta.setText("#" + (pos + 1));
        }
        @Override public int getItemCount() { return committedCodes.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView code, meta;
            VH(View v) {
                super(v);
                code = v.findViewById(R.id.tvCodeValue);
                meta = v.findViewById(R.id.tvCodeMeta);
            }
        }
    }
}