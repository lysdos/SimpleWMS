package com.wms.pda.ui.inventory;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.wms.pda.R;
import com.wms.pda.data.model.InventoryItem;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;

/**
 * 报废对话框:一物一码,固定报废 1 件。
 * 原因**选填、自由文本**,与 Web 端口径一致(此前是固定下拉选项,只能从 5 个预设里挑)。
 */
final class ScrapDialog {
    private ScrapDialog() {}

    static void show(Context ctx, InventoryItem item, Runnable onDone) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_scrap_simple, null, false);
        TextView tvInfo = v.findViewById(R.id.tv_info);
        EditText etReason = v.findViewById(R.id.et_reason);

        tvInfo.setText((item.sku == null ? "?" : item.sku) + " · " + (item.code == null ? "" : item.code));

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.scrap_title)
                .setView(v)
                .setPositiveButton(R.string.action_confirm, (d, w) -> {
                    String reason = etReason.getText() == null ? "" : etReason.getText().toString().trim();
                    RepositoryProvider.get().scrap(item.id, reason,
                            new WmsCallback<Void>() {
                                @Override public void onSuccess(Void v) {
                                    Toast.makeText(ctx, ctx.getString(R.string.scrap_done_fmt,
                                            item.sku == null ? "" : item.sku), Toast.LENGTH_SHORT).show();
                                    if (onDone != null) onDone.run();
                                }
                                @Override public void onError(int code, String msg) {
                                    Toast.makeText(ctx, ctx.getString(R.string.msg_scrap_failed, msg),
                                            Toast.LENGTH_SHORT).show();
                                }
                            });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
