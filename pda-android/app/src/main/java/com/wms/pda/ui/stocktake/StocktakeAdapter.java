package com.wms.pda.ui.stocktake;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.wms.pda.R;
import com.wms.pda.data.model.StocktakeOrder;
import com.wms.pda.ui.common.StatusPill;

import java.util.ArrayList;
import java.util.List;

/** 盘点单列表 Adapter。 */
class StocktakeAdapter extends RecyclerView.Adapter<StocktakeAdapter.VH> {

    interface OnClick { void onClick(StocktakeOrder o); }

    private final List<StocktakeOrder> data = new ArrayList<>();
    private final OnClick onClick;

    StocktakeAdapter(OnClick onClick) { this.onClick = onClick; }

    void submit(List<StocktakeOrder> list) {
        data.clear();
        data.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_stocktake, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        StocktakeOrder o = data.get(position);
        h.tvId.setText(o.id);
        StatusPill.apply(h.tvStatus, o.status);
        h.tvWarehouse.setText("盘点仓库 · " + (o.warehouse == null ? "-" : o.warehouse));
        h.tvMode.setText("盘点方式 · " + (o.mode == null ? "-" : o.mode.label));
        int total = o.lines == null ? 0 : o.lines.size();
        int done = 0;
        int diffSum = 0;
        if (o.lines != null) {
            for (StocktakeOrder.Line l : o.lines) {
                if (l.actualQty >= 0) done++;
                if (o.status == com.wms.pda.data.model.Enums.DocStatus.DONE) {
                    diffSum += l.diff();
                }
            }
        }
        String progress;
        if (o.status == com.wms.pda.data.model.Enums.DocStatus.DONE) {
            progress = total + " 行 · " + (diffSum == 0 ? "无差异" : "差异 " + (diffSum > 0 ? "+" : "") + diffSum);
        } else {
            progress = total + " 行 · 已盘 " + done + " 行";
        }
        h.tvProgress.setText(progress);
        h.tvTime.setText(o.createdAt == null ? "" : o.createdAt.substring(11));
        h.itemView.setOnClickListener(v -> onClick.onClick(o));
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvId, tvStatus, tvWarehouse, tvMode, tvProgress, tvTime;
        VH(View v) {
            super(v);
            tvId        = v.findViewById(R.id.tv_id);
            tvStatus    = v.findViewById(R.id.tv_status);
            tvWarehouse = v.findViewById(R.id.tv_warehouse);
            tvMode      = v.findViewById(R.id.tv_mode);
            tvProgress  = v.findViewById(R.id.tv_progress);
            tvTime      = v.findViewById(R.id.tv_time);
        }
    }
}