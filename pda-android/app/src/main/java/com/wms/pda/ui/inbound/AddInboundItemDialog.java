package com.wms.pda.ui.inbound;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.wms.pda.R;
import com.wms.pda.data.model.InboundOrder;
import com.wms.pda.data.model.Product;
import com.wms.pda.data.model.Supplier;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * 入库单明细弹窗(添加/编辑共用):
 * - 商品下拉(listProducts,编辑模式锁定商品)
 * - 数量
 * - 供应商下拉(listSuppliers,"不指定" 为默认项)
 * - 行备注
 */
final class AddInboundItemDialog {
    private AddInboundItemDialog() {}

    /** 添加模式 */
    static void show(Context ctx, Integer orderDbId, Runnable onDone) {
        loadAndShow(ctx, orderDbId, null, onDone);
    }

    /** 编辑模式:商品不可换,数量/供应商/行备注可改 */
    static void showEdit(Context ctx, Integer orderDbId, InboundOrder.InboundLine line, Runnable onDone) {
        loadAndShow(ctx, orderDbId, line, onDone);
    }

    private static void loadAndShow(Context ctx, Integer orderDbId,
                                    InboundOrder.InboundLine editLine, Runnable onDone) {
        final List<Product> products = new ArrayList<>();
        final List<Supplier> suppliers = new ArrayList<>();
        final boolean[] productFailed = {false};
        final int[] pending = {2};
        final Runnable settle = () -> {
            if (--pending[0] != 0) return;
            if (productFailed[0]) return;
            showLoaded(ctx, orderDbId, editLine, products, suppliers, onDone);
        };

        RepositoryProvider.get().listProducts(new WmsCallback<List<Product>>() {
            @Override public void onSuccess(List<Product> list) {
                if (list != null) products.addAll(list);
                settle.run();
            }
            @Override public void onError(int code, String msg) {
                productFailed[0] = true;
                Toast.makeText(ctx, ctx.getString(R.string.msg_load_products_failed) + ": " + msg, Toast.LENGTH_SHORT).show();
                settle.run();
            }
        });

        RepositoryProvider.get().listSuppliers(new WmsCallback<List<Supplier>>() {
            @Override public void onSuccess(List<Supplier> list) {
                if (list != null) suppliers.addAll(list);
                settle.run();
            }
            @Override public void onError(int code, String msg) {
                // 供应商加载失败不阻断,下拉只剩"不指定"
                settle.run();
            }
        });
    }

    private static void showLoaded(Context ctx, Integer orderDbId, InboundOrder.InboundLine editLine,
                                   List<Product> products, List<Supplier> suppliers, Runnable onDone) {
        if (editLine == null && products.isEmpty()) {
            Toast.makeText(ctx, R.string.msg_no_products, Toast.LENGTH_SHORT).show();
            return;
        }

        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_inbound_item, null, false);
        Spinner spProduct = v.findViewById(R.id.sp_product);
        EditText etQty = v.findViewById(R.id.et_qty);
        Spinner spSupplier = v.findViewById(R.id.sp_supplier);
        EditText etRowRemark = v.findViewById(R.id.et_row_remark);
        EditText etProductionDate = v.findViewById(R.id.et_production_date);
        EditText etShelfLife = v.findViewById(R.id.et_shelf_life);

        // 商品下拉
        List<String> productLabels = new ArrayList<>();
        for (Product p : products) {
            productLabels.add((p.sku == null ? "?" : p.sku) + " · " + (p.name == null ? "" : p.name));
        }
        // 编辑模式:定位原商品;商品列表里找不到(如商品已删)时把行上原信息插到首位兜底,
        // 避免静默显示成第一个商品、提交时换错商品
        int productIdx = 0;
        if (editLine != null) {
            int matched = -1;
            for (int i = 0; i < products.size(); i++) {
                if (editLine.productId != null && editLine.productId.equals(products.get(i).id)) { matched = i; break; }
            }
            if (matched < 0) {
                Product fallback = new Product();
                fallback.id = editLine.productId;
                fallback.sku = editLine.sku;
                fallback.name = editLine.name;
                products.add(0, fallback);
                productLabels.add(0, (editLine.sku == null ? "?" : editLine.sku)
                        + " · " + (editLine.name == null ? "" : editLine.name));
                productIdx = 0;
            } else {
                productIdx = matched;
            }
        }
        ArrayAdapter<String> productAd = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, productLabels);
        productAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spProduct.setAdapter(productAd);
        if (editLine != null) {
            spProduct.setSelection(Math.min(productIdx, productLabels.size() - 1));
            etQty.setText(String.valueOf(editLine.qty));
            etRowRemark.setText(editLine.rowRemark == null ? "" : editLine.rowRemark);
            etProductionDate.setText(editLine.productionDate == null ? "" : editLine.productionDate);
            if (editLine.shelfLifeDays != null) etShelfLife.setText(String.valueOf(editLine.shelfLifeDays));
        }

        // 生产日期:只读弹出 DatePicker
        etProductionDate.setOnClickListener(tv -> {
            int y, m, d;
            try {
                String[] parts = etProductionDate.getText().toString().split("-");
                y = Integer.parseInt(parts[0]); m = Integer.parseInt(parts[1]) - 1; d = Integer.parseInt(parts[2]);
            } catch (Exception e) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                y = cal.get(java.util.Calendar.YEAR); m = cal.get(java.util.Calendar.MONTH); d = cal.get(java.util.Calendar.DAY_OF_MONTH);
            }
            new android.app.DatePickerDialog(ctx, (picker, yy, mm, dd) ->
                    etProductionDate.setText(String.format("%04d-%02d-%02d", yy, mm + 1, dd)),
                    y, m, d).show();
        });

        // 供应商下拉:第一项固定"不指定"
        List<String> supplierLabels = new ArrayList<>();
        supplierLabels.add(ctx.getString(R.string.supplier_none));
        for (Supplier s : suppliers) supplierLabels.add(s.name == null ? "?" : s.name);
        ArrayAdapter<String> supplierAd = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, supplierLabels);
        supplierAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSupplier.setAdapter(supplierAd);
        if (editLine != null && editLine.supplier != null && !editLine.supplier.isEmpty()) {
            int idx = supplierLabels.indexOf(editLine.supplier);
            spSupplier.setSelection(idx >= 0 ? idx : 0);
        }

        // 选商品时带出默认保质期天数(仅添加模式且用户尚未手动填保质期时)
        spProduct.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                if (editLine != null || etShelfLife.getText().length() > 0) return;
                if (pos >= 0 && pos < products.size()) {
                    Integer dflt = products.get(pos).defaultShelfLifeDays;
                    etShelfLife.setText(dflt != null ? String.valueOf(dflt) : "");
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        // 初始带出(添加模式)
        if (editLine == null && !products.isEmpty()) {
            Integer dflt = products.get(0).defaultShelfLifeDays;
            if (dflt != null) etShelfLife.setText(String.valueOf(dflt));
        }

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(editLine == null ? R.string.inbound_add_item_action : R.string.action_edit)
                .setView(v)
                .setPositiveButton(R.string.action_confirm, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();

        // 软键盘弹出时压缩 Dialog 高度,配合内容 ScrollView,聚焦字段始终可见
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            Integer productId;
            int pIdx = spProduct.getSelectedItemPosition();
            if (pIdx >= 0 && pIdx < products.size()) {
                productId = products.get(pIdx).id;
            } else if (editLine != null) {
                productId = editLine.productId;   // 商品列表拉取失败时兜底用原商品
            } else {
                Toast.makeText(ctx, R.string.msg_no_products, Toast.LENGTH_SHORT).show();
                return;
            }
            if (productId == null) {
                Toast.makeText(ctx, "该商品缺少 ID,请刷新重试", Toast.LENGTH_SHORT).show();
                return;
            }
            int qty;
            try { qty = Integer.parseInt(etQty.getText().toString().trim()); }
            catch (Exception ex) { Toast.makeText(ctx, "请输入有效数量", Toast.LENGTH_SHORT).show(); return; }
            if (qty <= 0) { Toast.makeText(ctx, "数量必须大于 0", Toast.LENGTH_SHORT).show(); return; }

            int sIdx = spSupplier.getSelectedItemPosition();
            String supplier = (sIdx > 0 && sIdx < supplierLabels.size()) ? supplierLabels.get(sIdx) : null;
            String rowRemark = etRowRemark.getText().toString().trim();
            String productionDate = etProductionDate.getText().toString().trim();
            if (productionDate.isEmpty()) productionDate = null;
            Integer shelfLifeDays = null;
            try { String sl = etShelfLife.getText().toString().trim(); if (!sl.isEmpty()) shelfLifeDays = Integer.parseInt(sl); } catch (Exception ignored) {}

            String orderId = String.valueOf(orderDbId);
            WmsCallback<InboundOrder> cb = new WmsCallback<InboundOrder>() {
                @Override public void onSuccess(InboundOrder o) {
                    Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    if (onDone != null) onDone.run();
                }
                @Override public void onError(int code, String msg) {
                    Toast.makeText(ctx, "保存失败: " + msg, Toast.LENGTH_SHORT).show();
                }
            };

            if (editLine == null) {
                RepositoryProvider.get().addInboundItem(orderId, productId, qty, supplier, rowRemark,
                        productionDate, shelfLifeDays, cb);
            } else {
                RepositoryProvider.get().updateInboundItem(orderId, String.valueOf(editLine.id),
                        productId, qty, supplier, rowRemark, productionDate, shelfLifeDays, cb);
            }
        }));
        dialog.show();
    }
}
