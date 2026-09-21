package com.wms.pda.ui.stocktake;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.wms.pda.R;
import com.wms.pda.data.model.Enums;
import com.wms.pda.data.model.StocktakeOrder;
import com.wms.pda.ui.common.StatusPill;

import java.util.ArrayList;
import java.util.List;

/** 盘点单列表 Adapter。字段全部对接 /api/stock-checks。 */
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

        String scope = o.scope == null ? "ALL" : o.scope;
        String scopeLabel;
        if ("ALL".equals(scope))            scopeLabel = "全部在库单元";
        else if ("SKU".equals(scope))       scopeLabel = "按SKU";
        else if ("LOCATION".equals(scope))  scopeLabel = "按库位";
        else if ("WAREHOUSE".equals(scope)) scopeLabel = "按仓库";
        else                                scopeLabel = scope;
        h.tvMode.setText("方式 · " + scopeLabel);

        String keyword = o.scopeKeyword == null || o.scopeKeyword.isEmpty() ? "—" : o.scopeKeyword;
        h.tvScope.setText("范围 · " + keyword);

        h.tvOperator.setText(o.createdBy == null ? "创建人: —" : "创建人: " + o.createdBy);

        int total = o.totalCount == null ? 0 : o.totalCount;
        int counted = o.countedCount == null ? 0 : o.countedCount;
        int diff = o.diffCount == null ? 0 : o.diffCount;
        String progress;
        if (o.status == Enums.DocStatus.DONE) {
            progress = total + " 行 · " + (diff == 0 ? "无差异" : "差异 " + diff);
        } else {
            progress = total + " 行 · 已扫 " + counted + " 行";
        }
        h.tvProgress.setText(progress);

        h.tvTime.setText(o.createdAt == null ? "" :
                (o.createdAt.length() > 16 ? o.createdAt.substring(0, 16).replace("T", " ") : o.createdAt));

        h.itemView.setOnClickListener(v -> onClick.onClick(o));
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvId, tvStatus, tvMode, tvScope, tvOperator, tvProgress, tvTime;
        VH(View v) {
            super(v);
            tvId       = v.findViewById(R.id.tv_id);
            tvStatus   = v.findViewById(R.id.tv_status);
            tvMode     = v.findViewById(R.id.tv_mode);
            tvScope    = v.findViewById(R.id.tv_scope);
            tvOperator = v.findViewById(R.id.tv_operator);
            tvProgress = v.findViewById(R.id.tv_progress);
            tvTime     = v.findViewById(R.id.tv_time);
        }
    }
}