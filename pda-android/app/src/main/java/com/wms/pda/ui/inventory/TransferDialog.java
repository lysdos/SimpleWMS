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

import java.util.Arrays;
import java.util.List;

/** 调拨对话框:选目标仓库 + 输入数量。 */
final class TransferDialog {
    private TransferDialog() {}

    static void show(Context ctx, String sku, String fromWh, int available) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_transfer, null, false);
        TextView tv = v.findViewById(R.id.tv_from);
        Spinner sp = v.findViewById(R.id.sp_target);
        EditText et = v.findViewById(R.id.et_qty);

        tv.setText(sku + " · 从 " + fromWh + " 调出(可用 " + available + ")");
        // 列出其他仓库
        List<String> all = Arrays.asList("主仓", "分仓");
        List<String> options = new java.util.ArrayList<>();
        for (String s : all) if (!s.equals(fromWh)) options.add(s);
        if (options.isEmpty()) options.add("主仓");

        ArrayAdapter<String> ad = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, options);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.transfer_title)
                .setView(v)
                .setPositiveButton(R.string.action_confirm, (d, w) -> {
                    int qty;
                    try { qty = Integer.parseInt(et.getText().toString().trim()); }
                    catch (Exception ex) {
                        Toast.makeText(ctx, "请输入有效数量", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (qty <= 0 || qty > available) {
                        Toast.makeText(ctx, "数量超过可用数 " + available, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String to = (String) sp.getSelectedItem();
                    RepositoryProvider.get().transfer(sku, fromWh, to, qty,
                            new com.wms.pda.data.repo.WmsCallback<Void>() {
                        @Override public void onSuccess(Void v) {
                            Toast.makeText(ctx, "调拨完成", Toast.LENGTH_SHORT).show();
                        }
                        @Override public void onError(int code, String msg) {
                            Toast.makeText(ctx, "调拨失败: " + msg, Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
