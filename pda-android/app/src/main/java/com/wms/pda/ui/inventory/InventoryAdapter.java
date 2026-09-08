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
        void onPrint(String sku);
        void onTransfer(String sku, String warehouse, int available);
        void onScrap(String sku, String warehouse, int onHand);
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
        h.tvSku.setText(i.sku);
        h.tvName.setText(i.name);
        h.tvBarcode.setText("条码 " + i.barcode);
        h.tvLocation.setText(i.warehouse + " · " + i.location);
        h.tvOnHand.setText(i.onHand + " " + i.uom);
        h.tvAvailable.setText(i.available + " " + i.uom);
        h.tvLow.setVisibility(i.lowStock ? View.VISIBLE : View.GONE);

        h.btnPrint.setOnClickListener(v -> cb.onPrint(i.sku));
        h.btnTransfer.setOnClickListener(v -> cb.onTransfer(i.sku, i.warehouse, i.available));
        h.btnScrap.setOnClickListener(v -> cb.onScrap(i.sku, i.warehouse, i.onHand));
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvSku, tvName, tvBarcode, tvLocation, tvOnHand, tvAvailable, tvLow;
        final com.google.android.material.button.MaterialButton btnPrint, btnTransfer, btnScrap;
        VH(View v) {
            super(v);
            tvSku       = v.findViewById(R.id.tv_sku);
            tvName      = v.findViewById(R.id.tv_name);
            tvBarcode   = v.findViewById(R.id.tv_barcode);
            tvLocation  = v.findViewById(R.id.tv_location);
            tvOnHand    = v.findViewById(R.id.tv_on_hand);
            tvAvailable = v.findViewById(R.id.tv_available);
            tvLow       = v.findViewById(R.id.tv_low);
            btnPrint    = v.findViewById(R.id.btn_print);
            btnTransfer = v.findViewById(R.id.btn_transfer);
            btnScrap    = v.findViewById(R.id.btn_scrap);
        }
    }
}
