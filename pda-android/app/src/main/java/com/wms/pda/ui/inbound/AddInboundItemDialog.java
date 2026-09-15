package com.wms.pda.ui.inbound;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
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

        // 商品下拉
        List<String> productLabels = new ArrayList<>();
        for (Product p : products) {
            productLabels.add((p.sku == null ? "?" : p.sku) + " · " + (p.name == null ? "" : p.name));
        }
        // 编辑模式:定位原商品并锁定(商品列表拉不到时兜底显示行上原信息)
        int productIdx = 0;
        if (editLine != null) {
            for (int i = 0; i < products.size(); i++) {
                if (editLine.productId != null && editLine.productId.equals(products.get(i).id)) { productIdx = i; break; }
            }
            if (productLabels.isEmpty()) {
                productLabels.add((editLine.sku == null ? "?" : editLine.sku)
                        + " · " + (editLine.name == null ? "" : editLine.name));
            }
        }
        ArrayAdapter<String> productAd = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, productLabels);
        productAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spProduct.setAdapter(productAd);
        if (editLine != null) {
            spProduct.setSelection(Math.min(productIdx, productLabels.size() - 1));
            spProduct.setEnabled(false);
            etQty.setText(String.valueOf(editLine.qty));
            etRowRemark.setText(editLine.rowRemark == null ? "" : editLine.rowRemark);
        }

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

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(editLine == null ? R.string.inbound_add_item_action : R.string.action_edit)
                .setView(v)
                .setPositiveButton(R.string.action_confirm, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            Integer productId;
            if (editLine != null) {
                productId = editLine.productId;
            } else {
                int pIdx = spProduct.getSelectedItemPosition();
                if (pIdx < 0 || pIdx >= products.size()) {
                    Toast.makeText(ctx, R.string.msg_no_products, Toast.LENGTH_SHORT).show();
                    return;
                }
                productId = products.get(pIdx).id;
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
                RepositoryProvider.get().addInboundItem(orderId, productId, qty, supplier, rowRemark, cb);
            } else {
                RepositoryProvider.get().updateInboundItem(orderId, String.valueOf(editLine.id),
                        qty, supplier, rowRemark, cb);
            }
        }));
        dialog.show();
    }
}
