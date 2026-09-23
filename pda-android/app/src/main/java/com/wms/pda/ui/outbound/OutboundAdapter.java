package com.wms.pda.ui.outbound;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.wms.pda.R;
import com.wms.pda.data.model.Enums;
import com.wms.pda.data.model.OutboundOrder;
import com.wms.pda.ui.common.StatusPill;

import java.util.ArrayList;
import java.util.List;

class OutboundAdapter extends RecyclerView.Adapter<OutboundAdapter.VH> {

    interface OnClick { void onClick(OutboundOrder o); }
    interface OnEditHeader { void onEditHeader(OutboundOrder o); }
    interface OnCancel { void onCancel(OutboundOrder o); }

    private final List<OutboundOrder> data = new ArrayList<>();
    private final OnClick onClick;
    private final OnEditHeader onEditHeader;
    private final OnCancel onCancel;

    OutboundAdapter(OnClick onClick, OnEditHeader onEditHeader, OnCancel onCancel) {
        this.onClick = onClick;
        this.onEditHeader = onEditHeader;
        this.onCancel = onCancel;
    }

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

        Context ctx = h.tvId.getContext();
        h.tvCustomer.setText(ctx.getString(R.string.customer_prefix_fmt,
                o.customer == null || o.customer.isEmpty() ? "—" : o.customer));
        h.tvExternalNo.setText(ctx.getString(R.string.external_no_prefix_fmt,
                o.externalNo == null || o.externalNo.isEmpty() ? "—" : o.externalNo));
        h.tvOperator.setText(ctx.getString(R.string.created_by_fmt,
                o.createdBy == null ? "—" : o.createdBy));

        int itemCount = o.itemCount == null ? 0 : o.itemCount;
        int totalQty  = o.totalQuantity == null ? 0 : o.totalQuantity;
        h.tvSummary.setText(ctx.getString(R.string.lines_summary_fmt, itemCount, totalQty));

        h.tvTime.setText(o.createdAt == null ? "" :
                (o.createdAt.length() > 16 ? o.createdAt.substring(0, 16).replace("T", " ") : o.createdAt));

        // 仅 DRAFT 可编辑单头/取消
        boolean draft = o.status == Enums.DocStatus.DRAFT;
        h.llCardActions.setVisibility(draft ? View.VISIBLE : View.GONE);
        if (draft) {
            h.btnCardEdit.setOnClickListener(v -> onEditHeader.onEditHeader(o));
            h.btnCardCancel.setOnClickListener(v -> onCancel.onCancel(o));
        }

        h.itemView.setOnClickListener(v -> onClick.onClick(o));
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvId, tvStatus, tvCustomer, tvExternalNo, tvOperator, tvSummary, tvTime;
        final View llCardActions;
        final TextView btnCardEdit, btnCardCancel;
        VH(View v) {
            super(v);
            tvId         = v.findViewById(R.id.tv_id);
            tvStatus     = v.findViewById(R.id.tv_status);
            tvCustomer   = v.findViewById(R.id.tv_customer);
            tvExternalNo = v.findViewById(R.id.tv_external_no);
            tvOperator   = v.findViewById(R.id.tv_operator);
            tvSummary    = v.findViewById(R.id.tv_summary);
            tvTime       = v.findViewById(R.id.tv_time);
            llCardActions = v.findViewById(R.id.ll_card_actions);
            btnCardEdit   = v.findViewById(R.id.btn_card_edit);
            btnCardCancel = v.findViewById(R.id.btn_card_cancel);
        }
    }
}
