package com.wms.pda.ui.inventory;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.wms.pda.R;
import com.wms.pda.data.model.InventoryItem;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;
import com.wms.pda.ui.stocktake.StocktakeListActivity;

import java.util.List;

/** 库存管理:搜索 + 行内操作(打印条码/调拨/报废) + 跳转盘点。 */
public class InventoryFragment extends Fragment {

    private InventoryAdapter adapter;
    private EditText etSearch;
    private MaterialButton btnStocktake;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_inventory, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        RecyclerView list = v.findViewById(R.id.list);
        etSearch = v.findViewById(R.id.et_search);
        MaterialButton btnSearch = v.findViewById(R.id.btn_search);
        MaterialButton btnRefresh = v.findViewById(R.id.btn_refresh);
        btnStocktake = v.findViewById(R.id.btn_stocktake);

        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new InventoryAdapter(new InventoryAdapter.Callbacks() {
            @Override public void onPrint(String sku) {
                RepositoryProvider.get().printBarcode(sku, new WmsCallback<Void>() {
                    @Override public void onSuccess(Void v) {
                        if (isAdded()) Toast.makeText(requireContext(), "已生成条码: " + sku, Toast.LENGTH_SHORT).show();
                    }
                    @Override public void onError(int code, String msg) {
                        if (isAdded()) Toast.makeText(requireContext(), "生成失败: " + msg, Toast.LENGTH_SHORT).show();
                    }
                });
            }
            @Override public void onTransfer(String sku, String warehouse, int available) {
                TransferDialog.show(requireContext(), sku, warehouse, available);
            }
            @Override public void onScrap(String sku, String warehouse, int onHand) {
                ScrapDialog.show(requireContext(), sku, warehouse, onHand);
            }
        });
        list.setAdapter(adapter);

        btnSearch.setOnClickListener(x -> reload());
        btnRefresh.setOnClickListener(x -> { etSearch.setText(""); reload(); });
        btnStocktake.setOnClickListener(x ->
                startActivity(new Intent(requireContext(), StocktakeListActivity.class)));
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        String kw = etSearch == null ? "" : etSearch.getText().toString().trim();
        RepositoryProvider.get().searchInventory(kw, new WmsCallback<List<InventoryItem>>() {
            @Override public void onSuccess(List<InventoryItem> data) {
                if (!isAdded()) return;
                adapter.submit(data);
            }
            @Override public void onError(int code, String msg) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), "加载失败: " + msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}