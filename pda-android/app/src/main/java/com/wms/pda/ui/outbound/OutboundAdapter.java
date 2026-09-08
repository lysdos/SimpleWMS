package com.wms.pda.ui.outbound;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.wms.pda.R;
import com.wms.pda.data.model.OutboundOrder;
import com.wms.pda.ui.common.StatusPill;

import java.util.ArrayList;
import java.util.List;

class OutboundAdapter extends RecyclerView.Adapter<OutboundAdapter.VH> {

    interface OnClick { void onClick(OutboundOrder o); }

    private final List<OutboundOrder> data = new ArrayList<>();
    private final OnClick onClick;

    OutboundAdapter(OnClick onClick) { this.onClick = onClick; }

    void submit(List<OutboundOrder> list) {
        data.clear();
        data.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_outbound, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        OutboundOrder o = data.get(position);
        h.tvId.setText(o.id);
        StatusPill.apply(h.tvStatus, o.status);
        h.tvCustomer.setText("客户/部门: " + (o.customer == null ? "-" : o.customer));
        h.tvWarehouse.setText("出库仓库: " + (o.warehouse == null ? "-" : o.warehouse));
        h.tvLines.setText(o.lines == null ? "0 行" : (o.lines.size() + " 行"));
        h.tvTime.setText(o.createdAt == null ? "" : o.createdAt.substring(11));
        h.itemView.setOnClickListener(v -> onClick.onClick(o));
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvId, tvStatus, tvCustomer, tvWarehouse, tvLines, tvTime;
        VH(View v) {
            super(v);
            tvId        = v.findViewById(R.id.tv_id);
            tvStatus    = v.findViewById(R.id.tv_status);
            tvCustomer  = v.findViewById(R.id.tv_customer);
            tvWarehouse = v.findViewById(R.id.tv_warehouse);
            tvLines     = v.findViewById(R.id.tv_lines);
            tvTime      = v.findViewById(R.id.tv_time);
        }
    }
}
