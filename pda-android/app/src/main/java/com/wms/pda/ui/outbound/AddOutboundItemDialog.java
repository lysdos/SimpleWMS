package com.wms.pda.ui.outbound;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.wms.pda.R;
import com.wms.pda.data.model.Product;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * 出库单添加明细弹窗:商品下拉 + 数量 + 行备注。
 * 提交走 POST /api/outbound/orders/{id}/items,后端同步锁定库存单元,
 * 库存不足时返回错误(如"库存不足")并保留弹窗内容供修改。
 */
final class AddOutboundItemDialog {
    private AddOutboundItemDialog() {}

    static void show(Context ctx, Integer orderDbId, Runnable onDone) {
        RepositoryProvider.get().listProducts(new WmsCallback<List<Product>>() {
            @Override public void onSuccess(List<Product> list) {
                showLoaded(ctx, orderDbId, list == null ? new ArrayList<>() : list, onDone);
            }
            @Override public void onError(int code, String msg) {
                Toast.makeText(ctx, ctx.getString(R.string.msg_load_products_failed, msg),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void showLoaded(Context ctx, Integer orderDbId,
                                   List<Product> products, Runnable onDone) {
        if (products.isEmpty()) {
            Toast.makeText(ctx, R.string.msg_no_products, Toast.LENGTH_SHORT).show();
            return;
        }

        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_outbound_item, null, false);
        Spinner spProduct = v.findViewById(R.id.sp_product);
        EditText etQty = v.findViewById(R.id.et_qty);
        EditText etRowRemark = v.findViewById(R.id.et_row_remark);

        List<String> productLabels = new ArrayList<>();
        for (Product p : products) {
            productLabels.add((p.sku == null ? "?" : p.sku) + " · " + (p.name == null ? "" : p.name));
        }
        ArrayAdapter<String> productAd = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, productLabels);
        productAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spProduct.setAdapter(productAd);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(R.string.inbound_add_item_action)
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
            int pIdx = spProduct.getSelectedItemPosition();
            if (pIdx < 0 || pIdx >= products.size()) {
                Toast.makeText(ctx, R.string.msg_no_products, Toast.LENGTH_SHORT).show();
                return;
            }
            Product picked = products.get(pIdx);
            if (picked.id == null) {
                Toast.makeText(ctx, R.string.err_product_missing_id, Toast.LENGTH_SHORT).show();
                return;
            }
            int qty;
            try { qty = Integer.parseInt(etQty.getText().toString().trim()); }
            catch (Exception ex) { Toast.makeText(ctx, R.string.err_qty_invalid, Toast.LENGTH_SHORT).show(); return; }
            if (qty <= 0) { Toast.makeText(ctx, R.string.err_qty_positive, Toast.LENGTH_SHORT).show(); return; }

            String rowRemark = etRowRemark.getText().toString().trim();
            RepositoryProvider.get().addOutboundItem(String.valueOf(orderDbId), picked.id, qty,
                    rowRemark, new WmsCallback<com.wms.pda.data.model.OutboundOrder>() {
                        @Override public void onSuccess(com.wms.pda.data.model.OutboundOrder o) {
                            Toast.makeText(ctx, R.string.msg_added, Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                            if (onDone != null) onDone.run();
                        }
                        @Override public void onError(int code, String msg) {
                            Toast.makeText(ctx, ctx.getString(R.string.msg_add_failed, msg), Toast.LENGTH_SHORT).show();
                        }
                    });
        }));
        dialog.show();
    }
}
