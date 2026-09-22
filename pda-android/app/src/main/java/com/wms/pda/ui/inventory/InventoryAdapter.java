package com.wms.pda.ui.inventory;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.wms.pda.R;
import com.wms.pda.data.model.InventoryItem;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

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
        // 库位带上仓库(「主仓库/A-01-01」):一个商品会分散在多仓,只给一个库位码看不出在哪个仓。
        // 仓库名由后端列表带出(warehouseName),查不到就退回裸码 —— 与 Web 库存列表一致。
        String loc = (i.locationCode == null || i.locationCode.isEmpty()) ? "—" : i.locationCode;
        String wh  = i.warehouseName == null ? "" : i.warehouseName.trim();
        h.tvLocation.setText("库位: " + ("—".equals(loc) || wh.isEmpty() ? loc : wh + "/" + loc));
        bindExpiry(h, i);

        int qty = i.remaining == null ? 0 : i.remaining;
        h.tvQty.setText(String.valueOf(qty));

        String status = i.status == null ? "IN_STOCK" : i.status;
        // 文案与搜索栏的状态下拉共用同一套资源(见 InventoryFragment 顶部那排 R.string.inventory_status_*)
        if ("IN_STOCK".equals(status)) {
            h.tvStatus.setText(R.string.inventory_status_in_stock);
            h.tvStatus.setBackgroundResource(R.drawable.bg_pill_success);
        } else if ("OUT".equals(status)) {
            h.tvStatus.setText(R.string.inventory_status_out);
            h.tvStatus.setBackgroundResource(R.drawable.bg_pill_draft);
        } else if ("SCRAPPED".equals(status)) {
            h.tvStatus.setText(R.string.inventory_status_scrapped);
            h.tvStatus.setBackgroundResource(R.drawable.bg_pill_warning);
        } else if ("TRANSFERRED".equals(status)) {
            h.tvStatus.setText(R.string.inventory_status_transferred);
            h.tvStatus.setBackgroundResource(R.drawable.bg_pill_draft);
        } else if ("PENDING".equals(status)) {
            // 入库草稿单生成的占位单元,入库单提交后才变为在库
            h.tvStatus.setText(R.string.inventory_status_pending);
            h.tvStatus.setBackgroundResource(R.drawable.bg_pill_warning);
        } else if ("RESERVED".equals(status)) {
            // 已被某张出库草稿单预留(锁定),出库单提交后变为已出库
            h.tvStatus.setText(R.string.inventory_status_reserved);
            h.tvStatus.setBackgroundResource(R.drawable.bg_pill_draft);
        } else {
            // 认不出的状态:原样显示,别把信息吞掉
            h.tvStatus.setText(status);
            h.tvStatus.setBackgroundResource(R.drawable.bg_pill_draft);
        }

        h.btnTransfer.setOnClickListener(v -> cb.onTransfer(i));
        h.btnScrap.setOnClickListener(v -> cb.onScrap(i));
    }

    /**
     * 效期行:**保留到期日**,只在进入预警/过期时追加倒数。
     *
     * 规则与 Web 和后端的 EXPIRING 过滤一致:预警天数取商品的 expiry_warn_days,
     * 空则按 30 天(后端 SQL 是 COALESCE(p.expiry_warn_days, 30))。
     * 与 Web 的差别只有一处:Web 是用倒数**替换**日期,这里选择两个都留 —— 卡片只有一行,
     * 把具体日期换掉不划算,而「临期几天」是要的紧迫度信号(预警天数现场算不出来)。
     *
     * 颜色必须每次显式设置:行会复用,不清掉上一次的红色会串到下一张卡上。
     */
    private static void bindExpiry(VH h, InventoryItem i) {
        Context ctx = h.tvExpiry.getContext();
        String exp = i.expiryDate;
        if (exp == null || exp.isEmpty()) {
            h.tvExpiry.setText(R.string.inventory_expiry_none);
            h.tvExpiry.setTextColor(h.tvExpiryDefaultColor);
            return;
        }
        Integer days = daysUntil(exp);
        int warn = (i.expiryWarnDays == null) ? 30 : i.expiryWarnDays;
        if (days == null) {                       // 日期解析不了就只显示原值,别猜
            h.tvExpiry.setText(ctx.getString(R.string.inventory_expiry_fmt, exp));
            h.tvExpiry.setTextColor(h.tvExpiryDefaultColor);
        } else if (days < 0) {
            h.tvExpiry.setText(ctx.getString(R.string.inventory_expiry_expired_fmt, exp));
            h.tvExpiry.setTextColor(0xFFE74C3C);
        } else if (days <= warn) {
            h.tvExpiry.setText(ctx.getString(R.string.inventory_expiry_expiring_fmt, exp, days));
            h.tvExpiry.setTextColor(0xFFB45309);
        } else {
            h.tvExpiry.setText(ctx.getString(R.string.inventory_expiry_fmt, exp));
            h.tvExpiry.setTextColor(h.tvExpiryDefaultColor);
        }
    }

    /** 距到期日的天数(负数=已过期);解析失败返回 null。两边都归到当天 0 点再比。 */
    private static Integer daysUntil(String yyyyMMdd) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setLenient(false);
            java.util.Date d = f.parse(yyyyMMdd);
            if (d == null) return null;
            Calendar exp = Calendar.getInstance();
            exp.setTime(d);
            zeroTime(exp);
            Calendar today = Calendar.getInstance();
            zeroTime(today);
            long diff = exp.getTimeInMillis() - today.getTimeInMillis();
            return (int) Math.round(diff / 86400000.0);   // 除法的 DST 误差由 round 吸收
        } catch (Exception e) {
            return null;                                  // 脏数据不该让整页崩
        }
    }

    private static void zeroTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvSku, tvName, tvCode, tvLocation, tvExpiry, tvQty, tvStatus;
        final View btnTransfer, btnScrap;
        /** 效期行的默认字色(从 style 取一次),用来在复用时把「临期/过期」的颜色清回去 */
        int tvExpiryDefaultColor;
        VH(View v) {
            super(v);
            tvSku     = v.findViewById(R.id.tv_sku);
            tvName    = v.findViewById(R.id.tv_name);
            tvCode    = v.findViewById(R.id.tv_code);
            tvLocation = v.findViewById(R.id.tv_location);
            tvExpiry  = v.findViewById(R.id.tv_expiry);
            tvExpiryDefaultColor = tvExpiry.getCurrentTextColor();
            tvQty     = v.findViewById(R.id.tv_qty);
            tvStatus  = v.findViewById(R.id.tv_status);
            btnTransfer = v.findViewById(R.id.btn_transfer);
            btnScrap  = v.findViewById(R.id.btn_scrap);
        }
    }
}