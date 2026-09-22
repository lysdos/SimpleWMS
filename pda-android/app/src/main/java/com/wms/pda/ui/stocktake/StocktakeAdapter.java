package com.wms.pda.ui.stocktake;

import android.content.Context;
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

        // 这几行原先都是 Java 里把中文和值拼起来("方式 · " + 值),只翻值会变成
        // "方式 · All in-stock units" —— 所以整行做成带占位符的资源,一起走。
        Context ctx = h.tvMode.getContext();
        String scope = o.scope == null ? "ALL" : o.scope;
        int scopeRes = StocktakeLabels.scopeRes(scope);
        String scopeLabel = scopeRes == 0 ? scope : ctx.getString(scopeRes);
        h.tvMode.setText(ctx.getString(R.string.stocktake_mode_fmt, scopeLabel));

        String keyword = o.scopeKeyword == null || o.scopeKeyword.isEmpty() ? "—" : o.scopeKeyword;
        h.tvScope.setText(ctx.getString(R.string.stocktake_range_fmt, keyword));

        h.tvOperator.setText(ctx.getString(R.string.created_by_fmt,
                o.createdBy == null ? "—" : o.createdBy));

        int total = o.totalCount == null ? 0 : o.totalCount;
        int counted = o.countedCount == null ? 0 : o.countedCount;
        int diff = o.diffCount == null ? 0 : o.diffCount;
        h.tvProgress.setText(o.status == Enums.DocStatus.DONE
                ? (diff == 0 ? ctx.getString(R.string.stocktake_card_rows_ok, total)
                             : ctx.getString(R.string.stocktake_card_rows_diff, total, diff))
                : ctx.getString(R.string.stocktake_card_rows_scanned, total, counted));

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