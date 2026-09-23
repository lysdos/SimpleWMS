package com.wms.pda.ui.inventory;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.wms.pda.R;
import com.wms.pda.data.model.InventoryItem;
import com.wms.pda.data.model.Location;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;
import com.wms.pda.ui.common.LocationPicker;

import java.util.ArrayList;
import java.util.List;

/**
 * 调拨对话框:目标库位用**两级选择**(仓库 → 库位,见 {@link LocationPicker}),
 * 默认选中该单元当前所在仓库 —— 常见场景(同仓换货位)因此不用多点一次。
 * 一物一码,固定调拨 1 件。
 */
final class TransferDialog {
    private TransferDialog() {}

    static void show(Context ctx, InventoryItem item) {
        RepositoryProvider.get().listLocations(new WmsCallback<List<Location>>() {
            @Override public void onSuccess(List<Location> locs) {
                // 库存单元的 location_code 在库里是 NOT NULL,空值只可能是历史脏数据
                String currentLoc = item.locationCode == null ? "" : item.locationCode;
                List<Location> options = new ArrayList<>();
                if (locs != null) {
                    for (Location l : locs) {
                        // 不能调拨到自己
                        if (!l.code.equalsIgnoreCase(currentLoc)) options.add(l);
                    }
                }
                if (options.isEmpty()) {
                    Toast.makeText(ctx, R.string.transfer_no_other_location, Toast.LENGTH_SHORT).show();
                    return;
                }
                showPicker(ctx, item, currentLoc, locs, options);
            }
            @Override public void onError(int code, String msg) {
                Toast.makeText(ctx, ctx.getString(R.string.msg_load_locations_failed, msg),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void showPicker(Context ctx, InventoryItem item, String currentLoc,
                                   List<Location> all, List<Location> options) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_transfer_simple, null, false);
        TextView tvInfo = v.findViewById(R.id.tv_info);
        Spinner spWarehouse = v.findViewById(R.id.sp_warehouse);
        Spinner spTarget = v.findViewById(R.id.sp_target);

        tvInfo.setText((item.sku == null ? "?" : item.sku) + " · " + (item.code == null ? "" : item.code)
                + ctx.getString(R.string.transfer_current_location_fmt, currentLoc.isEmpty() ? "—" : currentLoc));

        // 默认选中该单元当前所在的仓库。**不能把 currentLoc 当 preferred** ——
        // 它在上面的候选里已被剔除(不能调拨到自己),查不到就会静默落到第一个仓库,
        // 用户看到头部写着"当前库位: 副仓/B-02"、不动手直接确认,货就被挪到了主仓。
        Integer curWarehouseId = null;
        if (all != null) {
            for (Location l : all) {
                if (currentLoc.equalsIgnoreCase(l.code)) { curWarehouseId = l.warehouseId; break; }
            }
        }
        LocationPicker picker = new LocationPicker(spWarehouse, spTarget, options, null);
        picker.selectWarehouse(curWarehouseId);

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.transfer_title)
                .setView(v)
                .setPositiveButton(R.string.action_confirm, (d, w) -> {
                    // 发给后端的永远是裸库位码。options 非空 → picker 必有选中项
                    String toLoc = picker.getSelectedCode();
                    RepositoryProvider.get().transfer(item.id, toLoc,
                            new WmsCallback<Void>() {
                                @Override public void onSuccess(Void v) {
                                    Toast.makeText(ctx, ctx.getString(R.string.transfer_done_fmt, toLoc),
                                            Toast.LENGTH_SHORT).show();
                                }
                                @Override public void onError(int code, String msg) {
                                    Toast.makeText(ctx, ctx.getString(R.string.msg_transfer_failed, msg),
                                            Toast.LENGTH_SHORT).show();
                                }
                            });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
