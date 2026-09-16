package com.wms.pda.ui.outbound;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.wms.pda.R;
import com.wms.pda.data.model.OutboundOrder;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;

import java.util.List;

/** 出库列表: 待出库 / 已出库 */
public class OutboundFragment extends Fragment {

    private boolean onlyPending = true;
    private OutboundAdapter adapter;
    private MaterialButton btnNew;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_outbound, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        TabLayout tabs = v.findViewById(R.id.tabs);
        RecyclerView list = v.findViewById(R.id.list);
        btnNew = v.findViewById(R.id.btn_new);
        btnNew.setEnabled(false);

        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new OutboundAdapter(
                o -> {
                    Intent i = new Intent(requireContext(), OutboundDetailActivity.class);
                    i.putExtra("orderId", o.id);
                    if (o.dbId != null) i.putExtra("orderDbId", o.dbId);
                    startActivity(i);
                },
                o -> EditOutboundHeaderDialog.show(requireContext(), o, this::reload),
                this::confirmCancelOrder);
        list.setAdapter(adapter);

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                onlyPending = tab.getPosition() == 0;
                reload();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        btnNew.setOnClickListener(x -> startActivity(new Intent(requireContext(), OutboundNewActivity.class)));
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    private void confirmCancelOrder(OutboundOrder o) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setMessage(R.string.confirm_cancel_outbound)
                .setPositiveButton(R.string.action_cancel_order, (d, w) ->
                        RepositoryProvider.get().cancelOutbound(String.valueOf(o.dbId),
                                new WmsCallback<Void>() {
                                    @Override public void onSuccess(Void v) {
                                        if (!isAdded()) return;
                                        Toast.makeText(requireContext(), R.string.msg_order_cancelled, Toast.LENGTH_SHORT).show();
                                        reload();
                                    }
                                    @Override public void onError(int code, String msg) {
                                        if (!isAdded()) return;
                                        Toast.makeText(requireContext(), "取消失败: " + msg, Toast.LENGTH_SHORT).show();
                                    }
                                }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void reload() {
        RepositoryProvider.get().listOutbound(onlyPending, new WmsCallback<List<OutboundOrder>>() {
            @Override public void onSuccess(List<OutboundOrder> data) {
                if (!isAdded()) return;
                adapter.submit(data);
                btnNew.setEnabled(true);
            }
            @Override public void onError(int code, String msg) {
                if (!isAdded()) return;
                android.widget.Toast.makeText(requireContext(), "加载失败: " + msg, android.widget.Toast.LENGTH_SHORT).show();
                btnNew.setEnabled(true);
            }
        });
    }
}