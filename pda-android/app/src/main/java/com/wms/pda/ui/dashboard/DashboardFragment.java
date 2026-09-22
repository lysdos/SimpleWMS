package com.wms.pda.ui.dashboard;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.wms.pda.R;
import com.wms.pda.data.model.DashboardSnapshot;
import com.wms.pda.data.model.OpLog;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.SessionStore;
import com.wms.pda.data.repo.WmsCallback;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/** 仪表盘:6 KPI(商品种类 / 库存总件数 / 库存预警 / 库存总值 / 临期预警 / 已过期)+ 最近操作。 */
public class DashboardFragment extends Fragment {

    private TextView kProduct, kQty, kLow, kValue, kExpiring, kExpired;
    private LinearLayout recent;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        TextView welcome = v.findViewById(R.id.tv_welcome);
        TextView tvOverview = v.findViewById(R.id.tv_overview_date);
        kProduct  = v.findViewById(R.id.kpi_product_count);
        kQty      = v.findViewById(R.id.kpi_total_quantity);
        kLow      = v.findViewById(R.id.kpi_low_stock);
        kValue    = v.findViewById(R.id.kpi_total_value);
        kExpiring = v.findViewById(R.id.kpi_expiring_soon);
        kExpired  = v.findViewById(R.id.kpi_expired);
        recent    = v.findViewById(R.id.recent_container);

        welcome.setText(getString(R.string.dash_welcome, SessionStore.currentUser()));
        tvOverview.setText(R.string.dash_overview);
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    /**
     * 流水类型(后端给原始值 IN/OUT/CHECK/TRANSFER/SCRAP)→ 界面文案。
     * 原来这步在数据层做(RemoteWmsRepository.mapLogType),那样拿不到 Context、也翻不了英文。
     * 认不出的类型按原样显示 —— 宁可露个生码,也别把信息吞掉。
     */
    private String logTypeLabel(String raw) {
        if (raw == null || raw.isEmpty()) return getString(R.string.log_type_other);
        switch (raw) {
            case "IN":       return getString(R.string.log_type_in);
            case "OUT":      return getString(R.string.log_type_out);
            case "CHECK":    return getString(R.string.log_type_check);
            case "TRANSFER": return getString(R.string.log_type_transfer);
            case "SCRAP":    return getString(R.string.log_type_scrap);
            default:         return raw;
        }
    }

    private void reload() {
        RepositoryProvider.get().loadDashboard(new WmsCallback<DashboardSnapshot>() {
            @Override public void onSuccess(DashboardSnapshot snap) {
                if (!isAdded() || snap == null) return;
                kProduct.setText(String.valueOf(snap.productCount));
                kQty.setText(String.valueOf(snap.totalQuantity));
                kLow.setText(String.valueOf(snap.lowStockSkuCount));
                kValue.setText(formatValue(snap.totalValue));
                kExpiring.setText(String.valueOf(snap.expiringSoonCount));
                kExpired.setText(String.valueOf(snap.expiredCount));

                recent.removeAllViews();
                LayoutInflater li = LayoutInflater.from(requireContext());
                List<OpLog> ops = snap.recentOps;
                if (ops == null || ops.isEmpty()) {
                    TextView empty = new TextView(requireContext());
                    empty.setText("暂无最近操作");
                    empty.setTextColor(0xFF9AA3B2);
                    empty.setPadding(24, 16, 24, 16);
                    recent.addView(empty);
                    return;
                }
                for (OpLog op : ops) {
                    View row = li.inflate(R.layout.item_op_log, recent, false);
                    ((TextView) row.findViewById(R.id.tv_type)).setText(logTypeLabel(op.type));
                    ((TextView) row.findViewById(R.id.tv_ref)).setText(op.refNo);
                    // op.time 在映射层已经是 "yyyy-MM-dd HH:mm"(RemoteWmsRepository 截好并换掉了 T),
                    // 这里**不要再切一刀**:原来 substring(11) 只留 "HH:mm",年月日就是这么丢的,
                    // 而且按固定下标切串一旦后端给更短的格式还会越界。
                    ((TextView) row.findViewById(R.id.tv_time)).setText(op.time == null ? "" : op.time);
                    ((TextView) row.findViewById(R.id.tv_summary)).setText(op.summary);
                    recent.addView(row);
                }
            }
            @Override public void onError(int code, String msg) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), "加载失败: " + msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static String formatValue(BigDecimal v) {
        if (v == null || v.signum() == 0) return "¥ —";
        return "¥ " + String.format(Locale.getDefault(), "%,d", v.longValue());
    }
}