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
        void onPrint(String code);
        void onScrap(String code, String warehouse, int onHand);
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
        } else {
            h.tvStatus.setText(status);
            h.tvStatus.setBackgroundResource(R.drawable.bg_pill_draft);
        }

        h.btnPrint.setOnClickListener(v -> cb.onPrint(i.code));
        h.btnScrap.setOnClickListener(v -> cb.onScrap(i.code, i.locationCode, qty));
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvSku, tvName, tvCode, tvLocation, tvQty, tvStatus;
        final View btnPrint, btnScrap;
        VH(View v) {
            super(v);
            tvSku     = v.findViewById(R.id.tv_sku);
            tvName    = v.findViewById(R.id.tv_name);
            tvCode    = v.findViewById(R.id.tv_code);
            tvLocation = v.findViewById(R.id.tv_location);
            tvQty     = v.findViewById(R.id.tv_qty);
            tvStatus  = v.findViewById(R.id.tv_status);
            btnPrint  = v.findViewById(R.id.btn_print);
            btnScrap  = v.findViewById(R.id.btn_scrap);
        }
    }
}