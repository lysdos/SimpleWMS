package com.wms.pda.ui.inbound;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
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
 * 入库登记页(替换原扫码页):
 * 选/建草稿单 → 选商品 + 数量 + 库位 + 备注 → 逐行 POST /items → 提交 → 显示 24 位码。
 *
 * 本 Activity 用 setContentView 在两个 layout 之间切换:
 * - R.layout.activity_inbound_scan       — 登记页(加商品/提交/取消)
 * - R.layout.activity_inbound_code_print — 提交后码打印页(打印全部/下载 CSV/返回)
 */
public class InboundScanActivity extends AppCompatActivity {

    private static final long SEARCH_DEBOUNCE_MS = 300L;

    private ApiClient api;
    private SessionStore session;

    /** 当前 DRAFT 订单 ID(从选/建单得到)。 */
    private Integer orderId;
    /** 当前订单详情(每次加商品后刷新)。 */
    private InboundOrderDto currentOrder;

    // ====== 登记页视图(register mode) ======
    private TextView tvOrderNo, tvStatus, tvCount;
    private Button btnAddItem, btnSubmit, btnCancel;
    private RecyclerView rvItems;
    private LineAdapter lineAdapter;

    // ====== 码打印页视图(print mode) ======
    private TextView tvPrintHeader, tvCodesCount;
    private RecyclerView rvCodes;
    private Button btnPrint, btnDownloadCsv, btnBack;

    private final List<InboundOrderItemDto> lines = new ArrayList<>();
    private final List<String> committedCodes = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = new SessionStore(this);
        api = new ApiClient(session);
        BeepUtil.init(this);

        showRegisterPage();
        showOrderPickerDialog();
    }

    // ============================================================
    // 页面切换
    // ============================================================

    private void showRegisterPage() {
        setContentView(R.layout.activity_inbound_scan);
        tvOrderNo = findViewById(R.id.tvOrderNo);
        tvStatus = findViewById(R.id.tvStatus);
        tvCount = findViewById(R.id.tvCount);
        btnAddItem = findViewById(R.id.btnAddItem);
        btnSubmit = findViewById(R.id.btnSubmit);
        btnCancel = findViewById(R.id.btnCancel);
        rvItems = findViewById(R.id.rvItems);
        rvItems.setLayoutManager(new LinearLayoutManager(this));
        lineAdapter = new LineAdapter();
        rvItems.setAdapter(lineAdapter);

        btnAddItem.setOnClickListener(v -> showAddItemDialog());
        btnSubmit.setOnClickListener(v -> doSubmit());
        btnCancel.setOnClickListener(v -> doCancel());

        // 没选单子时禁用按钮
        boolean hasOrder = orderId != null && currentOrder != null;
        btnAddItem.setEnabled(hasOrder && isDraft(currentOrder));
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
            // 取消选单 → 回 Home
            d.dismiss();
            if (orderId == null) finish();
        });
        b.setPositiveButton(R.string.inbound_picker_create_btn, (d, w) -> {
            // 创建新草稿单
            String ext = etExternalNo.getText() == null ? null : etExternalNo.getText().toString().trim();
            String rmk = etRemark.getText() == null ? null : etRemark.getText().toString().trim();
            if (ext != null && ext.isEmpty()) ext = null;
            if (rmk != null && rmk.isEmpty()) rmk = null;
            createDraft(ext, rmk, dialogRef[0]);
        });
        AlertDialog dialog = b.create();
        dialogRef[0] = dialog;
        dialog.setOnShowListener(d -> {
            // dialog 已显示,加载草稿单列表
            loadDrafts(drafts, pickerAdapter, tvPickerEmpty);
        });
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
                // 返回 Map { list, total, page, pageSize }
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
                runOnUiThread(() -> {
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(drafts.isEmpty() ? View.VISIBLE : View.GONE);
                });
            } catch (ApiException e) {
                runOnUiThread(() -> Toast.makeText(this,
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
                runOnUiThread(() -> {
                    if (dialog != null && dialog.isShowing()) dialog.dismiss();
                    if (created != null) loadOrder(created.id);
                });
            } catch (ApiException e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void loadOrder(Integer id) {
        this.orderId = id;
        new Thread(() -> {
            try {
                ApiResponse<InboundOrderDto> resp = api.get("/api/inbound/orders/" + id, InboundOrderDto.class);
                final InboundOrderDto o = resp == null ? null : resp.data;
                runOnUiThread(() -> {
                    currentOrder = o;
                    refreshLines();
                    showRegisterPage();
                });
            } catch (ApiException e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ============================================================
    // 加商品对话框
    // ============================================================

    private void showAddItemDialog() {
        if (orderId == null) return;
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_item, null, false);
        AutoCompleteTextView actvProduct = view.findViewById(R.id.actvProduct);
        TextInputEditText etQty = view.findViewById(R.id.etAddQty);
        TextInputEditText etLocation = view.findViewById(R.id.etAddLocation);
        TextInputEditText etSupplier = view.findViewById(R.id.etAddSupplier);
        TextInputEditText etRemark = view.findViewById(R.id.etAddRemark);

        final Integer[] selectedProductId = new Integer[1];
        final List<ProductDto> productResults = new ArrayList<>();
        final ArrayAdapter<String> productAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, new ArrayList<>());
        actvProduct.setAdapter(productAdapter);
        actvProduct.setThreshold(1);

        actvProduct.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String kw = s == null ? "" : s.toString().trim();
                selectedProductId[0] = null;
                if (kw.isEmpty()) return;
                if (pendingSearch != null) searchHandler.removeCallbacks(pendingSearch);
                pendingSearch = () -> searchProducts(kw, productResults, productAdapter);
                searchHandler.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
            }
        });
        actvProduct.setOnItemClickListener((parent, v, position, id) -> {
            if (position >= 0 && position < productResults.size()) {
                ProductDto p = productResults.get(position);
                selectedProductId[0] = p.id;
                actvProduct.setText(p.sku + " " + p.name);
                actvProduct.setSelection(actvProduct.getText().length());
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton(R.string.inbound_add, null) // 在 onShow 里接管
                .setNegativeButton(R.string.inbound_cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setOnClickListener(v -> {
                Integer productId = selectedProductId[0];
                String qtyStr = etQty.getText() == null ? "" : etQty.getText().toString().trim();
                String location = etLocation.getText() == null ? "" : etLocation.getText().toString().trim();
                String supplier = etSupplier.getText() == null ? "" : etSupplier.getText().toString().trim();
                String rowRemark = etRemark.getText() == null ? "" : etRemark.getText().toString().trim();
                if (productId == null) {
                    Toast.makeText(this, R.string.inbound_product_hint, Toast.LENGTH_SHORT).show();
                    return;
                }
                int qty;
                try { qty = Integer.parseInt(qtyStr); }
                catch (NumberFormatException e) {
                    Toast.makeText(this, R.string.inbound_quantity, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (qty <= 0) {
                    Toast.makeText(this, R.string.inbound_quantity, Toast.LENGTH_SHORT).show();
                    return;
                }
                addItem(productId, qty, location, supplier, rowRemark, dialog);
            });
        });
        dialog.show();
    }

    private void searchProducts(String keyword, List<ProductDto> results,
                                ArrayAdapter<String> adapter) {
        new Thread(() -> {
            try {
                String url = "/api/products?keyword=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
                // 后端 ApiResponse<List<Product>>.data 是 List<Map>,不是 Map — 拿到 List 让 ApiClient 帮我们反序列化
                ApiResponse<java.util.List> resp = api.get(url, java.util.List.class);
                @SuppressWarnings("unchecked")
                List<java.util.Map<String, Object>> rawList =
                        resp == null || resp.data == null ? null : resp.data;
                results.clear();
                List<String> labels = new ArrayList<>();
                if (rawList != null) {
                    for (java.util.Map<String, Object> m : rawList) {
                        ProductDto p = toProductDto(m);
                        results.add(p);
                        labels.add(p.sku + "  " + p.name);
                    }
                }
                runOnUiThread(() -> {
                    adapter.clear();
                    adapter.addAll(labels);
                    adapter.notifyDataSetChanged();
                });
            } catch (ApiException e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "搜索失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            } catch (Throwable t) {
                // 防御:任何其他异常(比如 Gson 类型不匹配)不让 App 崩
                runOnUiThread(() -> Toast.makeText(this,
                        "搜索异常: " + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void addItem(Integer productId, int qty, String location, String supplier,
                         String rowRemark, AlertDialog dialog) {
        Map<String, Object> body = new HashMap<>();
        body.put("productId", productId);
        body.put("quantity", qty);
        if (location != null && !location.isEmpty()) body.put("location", location);
        if (supplier != null && !supplier.isEmpty()) body.put("supplier", supplier);
        if (rowRemark != null && !rowRemark.isEmpty()) body.put("rowRemark", rowRemark);
        new Thread(() -> {
            try {
                ApiResponse<InboundOrderDto> resp = api.post(
                        "/api/inbound/orders/" + orderId + "/items", body, InboundOrderDto.class);
                final InboundOrderDto o = resp == null ? null : resp.data;
                runOnUiThread(() -> {
                    currentOrder = o;
                    refreshLines();
                    BeepUtil.success();
                    Toast.makeText(this, R.string.inbound_add_success, Toast.LENGTH_SHORT).show();
                    if (dialog != null && dialog.isShowing()) dialog.dismiss();
                });
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    BeepUtil.error();
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
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
                runOnUiThread(() -> {
                    BeepUtil.success();
                    currentOrder = o;
                    collectCommittedCodes(o);
                    btnSubmit.setEnabled(true);
                    showPrintPage();
                });
            } catch (ApiException e) {
                runOnUiThread(() -> {
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
                runOnUiThread(this::finish);
            } catch (ApiException e) {
                runOnUiThread(() -> {
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

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.inbound_share_chooser));
        send.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(send, getString(R.string.inbound_share_chooser)));
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
            try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
                w.write('﻿');
                w.write("入库单号," + csvField(currentOrder.orderNo) + "\n");
                w.write("外部单号," + csvField(currentOrder.externalNo) + "\n");
                w.write("操作员," + csvField(currentOrder.operator) + "\n");
                w.write("整单备注," + csvField(currentOrder.remark) + "\n");
                w.write("总数量," + (currentOrder.totalQuantity == null ? 0 : currentOrder.totalQuantity) + "\n");
                w.write("明细数," + (currentOrder.itemCount == null ? 0 : currentOrder.itemCount) + "\n\n");
                w.write("序号,供应商,SKU,商品名称,库位,数量,行备注,24位码\n");
                int idx = 1;
                if (currentOrder.items != null) {
                    for (InboundOrderItemDto it : currentOrder.items) {
                        String codesStr = (it.codes == null || it.codes.isEmpty())
                                ? "" : android.text.TextUtils.join("|", it.codes);
                        w.write((idx++) + ","
                                + csvField(it.supplier) + ","
                                + csvField(it.sku) + ","
                                + csvField(it.productName) + ","
                                + csvField(it.location) + ","
                                + (it.quantity == null ? 0 : it.quantity) + ","
                                + csvField(it.rowRemark) + ","
                                + csvField(codesStr) + "\n");
                    }
                }
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.inbound_codes_saved, out.getAbsolutePath()),
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
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
            tvCount.setText(getString(R.string.inbound_order_meta,
                    currentOrder == null ? "" : currentOrder.orderNo,
                    total, lines.size()));
        }
        if (btnAddItem != null) {
            boolean canEdit = isDraft(currentOrder);
            btnAddItem.setEnabled(canEdit);
            btnSubmit.setEnabled(canEdit && !lines.isEmpty());
            btnCancel.setEnabled(canEdit);
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

    @SuppressWarnings("unchecked")
    private static ProductDto toProductDto(java.util.Map<String, Object> m) {
        ProductDto p = new ProductDto();
        p.id = asInt(m.get("id"));
        p.sku = asStr(m.get("sku"));
        p.name = asStr(m.get("name"));
        p.category = asStr(m.get("category"));
        p.unit = asStr(m.get("unit"));
        p.location = asStr(m.get("location"));
        p.quantity = asInt(m.get("quantity"));
        p.lowStock = asInt(m.get("lowStock"));
        p.remark = asStr(m.get("remark"));
        return p;
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
                // PDA 不实现删除单行接口(后端没暴露)。只是提示。
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