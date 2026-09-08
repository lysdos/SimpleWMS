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

        h.tvExternalNo.setText(o.externalNo == null || o.externalNo.isEmpty()
                ? "外部单号: —" : "外部单号: " + o.externalNo);
        h.tvOperator.setText(o.operator == null ? "操作员: —" : "操作员: " + o.operator);

        // 后端 itemCount / totalQuantity 是真实数据;lines 可能为 null(列表接口不带 items)
        int itemCount = o.itemCount == null ? 0 : o.itemCount;
        int totalQty = o.totalQuantity == null ? 0 : o.totalQuantity;
        h.tvSummary.setText(itemCount + " 行 · " + totalQty + " 件");

        h.tvTime.setText(o.createdAt == null ? "" :
                (o.createdAt.length() > 16 ? o.createdAt.substring(0, 16).replace("T", " ") : o.createdAt));

        h.itemView.setOnClickListener(v -> onClick.onClick(o));
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvId, tvStatus, tvExternalNo, tvOperator, tvSummary, tvTime;
        VH(View v) {
            super(v);
            tvId         = v.findViewById(R.id.tv_id);
            tvStatus     = v.findViewById(R.id.tv_status);
            tvExternalNo = v.findViewById(R.id.tv_external_no);
            tvOperator   = v.findViewById(R.id.tv_operator);
            tvSummary    = v.findViewById(R.id.tv_summary);
            tvTime       = v.findViewById(R.id.tv_time);
        }
    }
}