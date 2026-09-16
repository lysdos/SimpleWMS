package com.wms.pda.ui.inventory;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.wms.pda.R;
import com.wms.pda.data.model.InventoryItem;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;

/** 报废对话框:一物一码,固定报废 1 件,只需选原因。 */
final class ScrapDialog {
    private ScrapDialog() {}

    private static final String[] REASONS = { "过期", "损坏", "性能不达标", "客户退货", "其它" };

    static void show(Context ctx, InventoryItem item, Runnable onDone) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_scrap_simple, null, false);
        TextView tvInfo = v.findViewById(R.id.tv_info);
        Spinner spReason = v.findViewById(R.id.sp_reason);

        tvInfo.setText((item.sku == null ? "?" : item.sku) + " · " + (item.code == null ? "" : item.code));

        ArrayAdapter<String> ad = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, REASONS);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spReason.setAdapter(ad);

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.scrap_title)
                .setView(v)
                .setPositiveButton(R.string.action_confirm, (d, w) -> {
                    String reason = (String) spReason.getSelectedItem();
                    RepositoryProvider.get().scrap(item.id, reason,
                            new WmsCallback<Void>() {
                                @Override public void onSuccess(Void v) {
                                    Toast.makeText(ctx, "已报废 " + (item.sku == null ? "" : item.sku), Toast.LENGTH_SHORT).show();
                                    if (onDone != null) onDone.run();
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
