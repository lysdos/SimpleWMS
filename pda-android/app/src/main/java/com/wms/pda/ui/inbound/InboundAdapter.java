package com.wms.pda.ui.inbound;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.wms.pda.R;
import com.wms.pda.data.model.InboundOrder;
import com.wms.pda.ui.common.StatusPill;

import java.util.ArrayList;
import java.util.List;

class InboundAdapter extends RecyclerView.Adapter<InboundAdapter.VH> {

    interface OnClick { void onClick(InboundOrder o); }

    private final List<InboundOrder> data = new ArrayList<>();
    private final OnClick onClick;

    InboundAdapter(OnClick onClick) { this.onClick = onClick; }

    void submit(List<InboundOrder> list) {
        data.clear();
        data.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_inbound, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        InboundOrder o = data.get(position);
        h.tvId.setText(o.id);
        StatusPill.apply(h.tvStatus, o.status);
        h.tvSupplier.setText("供应商: " + (o.supplier == null ? "-" : o.supplier));
        h.tvWarehouse.setText("目标仓库: " + (o.warehouse == null ? "-" : o.warehouse));
        h.tvLines.setText(o.lines == null ? "0 行" : (o.lines.size() + " 行"));
        h.tvTime.setText(o.createdAt == null ? "" : o.createdAt.substring(11));
        h.itemView.setOnClickListener(v -> onClick.onClick(o));
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvId, tvStatus, tvSupplier, tvWarehouse, tvLines, tvTime;
        VH(View v) {
            super(v);
            tvId        = v.findViewById(R.id.tv_id);
            tvStatus    = v.findViewById(R.id.tv_status);
            tvSupplier  = v.findViewById(R.id.tv_supplier);
            tvWarehouse = v.findViewById(R.id.tv_warehouse);
            tvLines     = v.findViewById(R.id.tv_lines);
            tvTime      = v.findViewById(R.id.tv_time);
        }
    }
}
