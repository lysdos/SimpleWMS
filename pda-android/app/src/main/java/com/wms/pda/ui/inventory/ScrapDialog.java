package com.wms.pda.ui.inventory;

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
import com.wms.pda.data.repo.RepositoryProvider;

/** 报废对话框:输入数量 + 选原因。 */
final class ScrapDialog {
    private ScrapDialog() {}

    private static final String[] REASONS = { "过期", "损坏", "性能不达标", "客户退货", "其它" };

    static void show(Context ctx, String sku, String warehouse, int onHand) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_scrap, null, false);
        TextView tvInfo = v.findViewById(R.id.tv_info);
        EditText etQty = v.findViewById(R.id.et_qty);
        Spinner spReason = v.findViewById(R.id.sp_reason);

        tvInfo.setText(sku + " · " + warehouse + "( 在库 " + onHand + " )");

        ArrayAdapter<String> ad = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, REASONS);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spReason.setAdapter(ad);

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.scrap_title)
                .setView(v)
                .setPositiveButton(R.string.action_confirm, (d, w) -> {
                    int qty;
                    try { qty = Integer.parseInt(etQty.getText().toString().trim()); }
                    catch (Exception ex) {
                        Toast.makeText(ctx, "请输入有效数量", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (qty <= 0 || qty > onHand) {
                        Toast.makeText(ctx, "数量超过在库数 " + onHand, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String reason = (String) spReason.getSelectedItem();
                    RepositoryProvider.get().scrap(sku, warehouse, qty, reason,
                            new com.wms.pda.data.repo.WmsCallback<Void>() {
                        @Override public void onSuccess(Void v) {
                            Toast.makeText(ctx, "已报废 " + sku + " × " + qty, Toast.LENGTH_SHORT).show();
                        }
                        @Override public void onError(int code, String msg) {
                            Toast.makeText(ctx, "报废失败: " + msg, Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}