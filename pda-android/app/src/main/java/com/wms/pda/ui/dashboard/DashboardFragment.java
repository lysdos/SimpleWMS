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

import java.util.List;

/** 仪表盘:4 KPI + 最近操作。 */
public class DashboardFragment extends Fragment {

    private TextView k1, k2, k3, k4;
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
        welcome.setText(getString(R.string.dash_welcome, SessionStore.currentUser()));

        k1 = v.findViewById(R.id.kpi_inbound_today);
        k2 = v.findViewById(R.id.kpi_outbound_today);
        k3 = v.findViewById(R.id.kpi_pending_stocktake);
        k4 = v.findViewById(R.id.kpi_low_stock);
        recent = v.findViewById(R.id.recent_container);
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        RepositoryProvider.get().loadDashboard(new WmsCallback<DashboardSnapshot>() {
            @Override public void onSuccess(DashboardSnapshot snap) {
                if (!isAdded() || snap == null) return;
                k1.setText(String.valueOf(snap.inboundToday));
                k2.setText(String.valueOf(snap.outboundToday));
                k3.setText(String.valueOf(snap.pendingStocktake));
                k4.setText(String.valueOf(snap.lowStockSkuCount));

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
                    ((TextView) row.findViewById(R.id.tv_type)).setText(op.type);
                    ((TextView) row.findViewById(R.id.tv_ref)).setText(op.refNo);
                    ((TextView) row.findViewById(R.id.tv_time)).setText(op.time == null ? "" : op.time.substring(11));
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
}