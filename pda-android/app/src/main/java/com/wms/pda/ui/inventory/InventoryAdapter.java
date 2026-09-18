package com.wms.pda.ui.inventory;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.wms.pda.R;
import com.wms.pda.data.model.InventoryItem;

import java.util.ArrayList;
import java.util.List;

class InventoryAdapter extends RecyclerView.Adapter<InventoryAdapter.VH> {

    interface Callbacks {
        void onTransfer(InventoryItem item);
        void onScrap(InventoryItem item);
    }

    private final List<InventoryItem> data = new ArrayList<>();
    private final Callbacks cb;

    InventoryAdapter(Callbacks cb) { this.cb = cb; }

    void submit(List<InventoryItem> list) {
        data.clear();
        data.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_inventory, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        InventoryItem i = data.get(position);
        h.tvSku.setText(i.sku == null ? "?" : i.sku);
        h.tvName.setText(i.productName == null ? "" : i.productName);
        h.tvCode.setText(i.code == null ? "" : i.code);
        h.tvLocation.setText("库位: " + (i.locationCode == null ? "—" : i.locationCode));

        int qty = i.remaining == null ? 0 : i.remaining;
        h.tvQty.setText(String.valueOf(qty));

        String status = i.status == null ? "IN_STOCK" : i.status;
        if ("IN_STOCK".equals(status)) {
            h.tvStatus.setText("在库");
            h.tvStatus.setBackgroundResource(R.drawable.bg_pill_success);
        } else if ("OUT".equals(status)) {
            h.tvStatus.setText("已出库");
            h.tvStatus.setBackgroundResource(R.drawable.bg_pill_draft);
        } else if ("SCRAPPED".equals(status)) {
            h.tvStatus.setText("已报废");
            h.tvStatus.setBackgroundResource(R.drawable.bg_pill_warning);
        } else if ("TRANSFERRED".equals(status)) {
            h.tvStatus.setText("已调拨");
            h.tvStatus.setBackgroundResource(R.drawable.bg_pill_draft);
        } else if ("PENDING".equals(status)) {
            // 入库草稿单生成的占位单元,入库单提交后才变为在库
            h.tvStatus.setText("待入库");
            h.tvStatus.setBackgroundResource(R.drawable.bg_pill_warning);
        } else if ("RESERVED".equals(status)) {
            // 已被某张出库草稿单预留(锁定),出库单提交后变为已出库
            h.tvStatus.setText("已预留");
            h.tvStatus.setBackgroundResource(R.drawable.bg_pill_draft);
        } else {
            h.tvStatus.setText(status);
            h.tvStatus.setBackgroundResource(R.drawable.bg_pill_draft);
        }

        h.btnTransfer.setOnClickListener(v -> cb.onTransfer(i));
        h.btnScrap.setOnClickListener(v -> cb.onScrap(i));
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvSku, tvName, tvCode, tvLocation, tvQty, tvStatus;
        final View btnTransfer, btnScrap;
        VH(View v) {
            super(v);
            tvSku     = v.findViewById(R.id.tv_sku);
            tvName    = v.findViewById(R.id.tv_name);
            tvCode    = v.findViewById(R.id.tv_code);
            tvLocation = v.findViewById(R.id.tv_location);
            tvQty     = v.findViewById(R.id.tv_qty);
            tvStatus  = v.findViewById(R.id.tv_status);
            btnTransfer = v.findViewById(R.id.btn_transfer);
            btnScrap  = v.findViewById(R.id.btn_scrap);
        }
    }
}