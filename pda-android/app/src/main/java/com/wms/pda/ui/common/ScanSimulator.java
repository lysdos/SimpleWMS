package com.wms.pda.ui.common;

import android.content.Context;
import android.text.InputType;
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
import com.wms.pda.data.repo.WmsCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * 扫码模拟对话框:可手动输入条码,或从预置条码下拉里选。
 * 真实环境换成 CameraX + ML Kit 的 ScanActivity。
 */
public final class ScanSimulator {
    private ScanSimulator() {}

    public interface OnResult {
        void onResult(String barcode);
    }

    /** 弹一个扫码模拟对话框 */
    public static void show(Context ctx, OnResult cb) {
        View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_scan_simulator, null, false);
        EditText input = root.findViewById(R.id.et_scan_input);
        final Spinner sp = root.findViewById(R.id.sp_scan_preset);

        input.setInputType(InputType.TYPE_CLASS_TEXT);

        // 异步加载预置条码
        final ArrayAdapter<String> ad = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, new ArrayList<>());
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        RepositoryProvider.get().searchInventory("", new WmsCallback<List<com.wms.pda.data.model.InventoryItem>>() {
            @Override public void onSuccess(List<com.wms.pda.data.model.InventoryItem> items) {
                ad.clear();
                if (items != null) {
                    for (com.wms.pda.data.model.InventoryItem i : items) {
                        ad.add(i.barcode + "  (" + i.name + ")");
                    }
                }
                ad.notifyDataSetChanged();
            }
            @Override public void onError(int code, String msg) { /* spinner 留空 */ }
        });

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(R.string.scan_dialog_title)
                .setView(root)
                .setPositiveButton(R.string.action_confirm, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String code = input.getText().toString().trim();
                if (code.isEmpty() && sp.getSelectedItem() != null) {
                    String sel = (String) sp.getSelectedItem();
                    int idx = sel.indexOf("  (");
                    code = idx > 0 ? sel.substring(0, idx) : sel;
                }
                if (code.isEmpty()) {
                    Toast.makeText(ctx, "请输入或选择条码", Toast.LENGTH_SHORT).show();
                    return;
                }
                cb.onResult(code);
                dialog.dismiss();
            });
        });
        dialog.show();
    }
}