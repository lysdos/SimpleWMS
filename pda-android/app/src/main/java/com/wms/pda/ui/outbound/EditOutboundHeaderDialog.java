package com.wms.pda.ui.outbound;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.wms.pda.R;
import com.wms.pda.data.model.OutboundOrder;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;

/** 编辑 DRAFT 出库单头(客户/外部单号/备注)。 */
final class EditOutboundHeaderDialog {
    private EditOutboundHeaderDialog() {}

    static void show(Context ctx, OutboundOrder order, Runnable onDone) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_outbound_header, null, false);
        EditText etCustomer = v.findViewById(R.id.et_customer);
        EditText etExternalNo = v.findViewById(R.id.et_external_no);
        EditText etRemark = v.findViewById(R.id.et_remark);

        etCustomer.setText(order.customer == null ? "" : order.customer);
        etExternalNo.setText(order.externalNo == null ? "" : order.externalNo);
        etRemark.setText(order.remark == null ? "" : order.remark);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(R.string.outbound_edit_header_title)
                .setView(v)
                .setPositiveButton(R.string.action_confirm, (d, w) -> {
                    String customer = etCustomer.getText().toString().trim();
                    String externalNo = etExternalNo.getText().toString().trim();
                    String remark = etRemark.getText().toString().trim();
                    RepositoryProvider.get().updateOutboundHeader(String.valueOf(order.dbId),
                            customer, externalNo, remark, new WmsCallback<OutboundOrder>() {
                                @Override public void onSuccess(OutboundOrder o) {
                                    Toast.makeText(ctx, R.string.msg_header_saved, Toast.LENGTH_SHORT).show();
                                    if (onDone != null) onDone.run();
                                }
                                @Override public void onError(int code, String msg) {
                                    Toast.makeText(ctx, ctx.getString(R.string.msg_save_failed, msg),
                                            Toast.LENGTH_SHORT).show();
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
