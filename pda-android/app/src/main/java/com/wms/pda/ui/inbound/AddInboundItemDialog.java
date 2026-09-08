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
import com.wms.pda.data.model.InventoryItem;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;

import java.util.ArrayList;
import java.util.List;

/** 入库单添加明细:选商品 + 数量 + 行级 supplier。 */
final class AddInboundItemDialog {
    private AddInboundItemDialog() {}

    static void show(Context ctx, List<InventoryItem> items, String orderId, Runnable onDone) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_inbound_item, null, false);
        Spinner spProduct = v.findViewById(R.id.sp_product);
        EditText etQty = v.findViewById(R.id.et_qty);
        EditText etSupplier = v.findViewById(R.id.et_supplier);

        List<String> labels = new ArrayList<>();
        for (InventoryItem i : items) labels.add(i.sku + " · " + i.name);
        ArrayAdapter<String> ad = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spProduct.setAdapter(ad);

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.inbound_add_item_action)
                .setView(v)
                .setPositiveButton(R.string.action_confirm, (d, w) -> {
                    int idx = spProduct.getSelectedItemPosition();
                    InventoryItem picked = items.get(idx);
                    int qty;
                    try { qty = Integer.parseInt(etQty.getText().toString().trim()); }
                    catch (Exception ex) { Toast.makeText(ctx, "请输入有效数量", Toast.LENGTH_SHORT).show(); return; }
                    if (qty <= 0) { Toast.makeText(ctx, "数量必须大于 0", Toast.LENGTH_SHORT).show(); return; }
                    String supplier = etSupplier.getText().toString().trim();

                    // 后端需要 productId;InventoryItem 现在携带了 productId(Mappers.toModel 同步)。
                    Integer productId = picked.productId;
                    if (productId == null) {
                        Toast.makeText(ctx, "该库存行缺少商品 ID,请刷新", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    RepositoryProvider.get().addInboundItem(orderId, productId,
                            qty, supplier, null, new WmsCallback<com.wms.pda.data.model.InboundOrder>() {
                                @Override public void onSuccess(com.wms.pda.data.model.InboundOrder v) {
                                    Toast.makeText(ctx, "已添加", Toast.LENGTH_SHORT).show();
                                    if (onDone != null) onDone.run();
                                }
                                @Override public void onError(int code, String msg) {
                                    Toast.makeText(ctx, "添加失败: " + msg, Toast.LENGTH_SHORT).show();
                                }
                            });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}