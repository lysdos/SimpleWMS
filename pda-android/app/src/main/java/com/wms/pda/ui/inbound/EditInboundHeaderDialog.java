package com.wms.pda.ui.inbound;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.wms.pda.R;
import com.wms.pda.data.model.InboundOrder;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;

/** 编辑 DRAFT 入库单头(外部单号/备注)。 */
final class EditInboundHeaderDialog {
    private EditInboundHeaderDialog() {}

    static void show(Context ctx, InboundOrder order, Runnable onDone) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_inbound_header, null, false);
        EditText etExternalNo = v.findViewById(R.id.et_external_no);
        EditText etRemark = v.findViewById(R.id.et_remark);

        etExternalNo.setText(order.externalNo == null ? "" : order.externalNo);
        etRemark.setText(order.remark == null ? "" : order.remark);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(R.string.inbound_edit_header_title)
                .setView(v)
                .setPositiveButton(R.string.action_confirm, (d, w) -> {
                    String externalNo = etExternalNo.getText().toString().trim();
                    String remark = etRemark.getText().toString().trim();
                    RepositoryProvider.get().updateInboundHeader(String.valueOf(order.dbId),
                            externalNo, remark, new WmsCallback<InboundOrder>() {
                                @Override public void onSuccess(InboundOrder o) {
                                    Toast.makeText(ctx, R.string.msg_header_saved, Toast.LENGTH_SHORT).show();
                                    if (onDone != null) onDone.run();
                                }
                                @Override public void onError(int code, String msg) {
                                    Toast.makeText(ctx, "保存失败: " + msg, Toast.LENGTH_SHORT).show();
                                }
                            });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
    }
}
