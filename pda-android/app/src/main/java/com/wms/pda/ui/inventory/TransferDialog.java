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

import java.util.ArrayList;
import java.util.List;

/** 调拨对话框:库位从后端拉取(排除当前库位),一物一码固定调拨 1 件。 */
final class TransferDialog {
    private TransferDialog() {}

    static void show(Context ctx, InventoryItem item) {
        RepositoryProvider.get().listLocations(new WmsCallback<List<String>>() {
            @Override public void onSuccess(List<String> locs) {
                String currentLoc = item.locationCode == null ? "默认库位" : item.locationCode;
                List<String> options = new ArrayList<>();
                if (locs != null) {
                    for (String loc : locs) {
                        if (!loc.equalsIgnoreCase(currentLoc)) options.add(loc);
                    }
                }
                if (options.isEmpty()) {
                    Toast.makeText(ctx, "没有其他可用库位,请先在 PC 端维护库位", Toast.LENGTH_SHORT).show();
                    return;
                }
                showPicker(ctx, item, currentLoc, options);
            }
            @Override public void onError(int code, String msg) {
                Toast.makeText(ctx, "加载库位失败: " + msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void showPicker(Context ctx, InventoryItem item, String currentLoc, List<String> options) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_transfer_simple, null, false);
        TextView tvInfo = v.findViewById(R.id.tv_info);
        Spinner spTarget = v.findViewById(R.id.sp_target);

        tvInfo.setText((item.sku == null ? "?" : item.sku) + " · " + (item.code == null ? "" : item.code)
                + "\n当前库位: " + currentLoc);

        ArrayAdapter<String> ad = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, options);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spTarget.setAdapter(ad);

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.transfer_title)
                .setView(v)
                .setPositiveButton(R.string.action_confirm, (d, w) -> {
                    String toLoc = (String) spTarget.getSelectedItem();
                    RepositoryProvider.get().transfer(item.id, toLoc,
                            new WmsCallback<Void>() {
                                @Override public void onSuccess(Void v) {
                                    Toast.makeText(ctx, "已调拨到 " + toLoc, Toast.LENGTH_SHORT).show();
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
