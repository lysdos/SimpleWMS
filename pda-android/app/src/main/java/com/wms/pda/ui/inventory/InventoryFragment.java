package com.wms.pda.ui.inventory;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
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
import com.wms.pda.data.repo.SessionStore;
import com.wms.pda.data.repo.WmsCallback;
import com.wms.pda.ui.stocktake.StocktakeListActivity;

import java.util.List;

/** 库存管理:搜索 + 行内操作(调拨/报废) + 跳转盘点。 */
public class InventoryFragment extends Fragment {

    private InventoryAdapter adapter;
    private EditText etSearch;
    private Spinner spStatus, spExpiry;
    private MaterialButton btnStocktake;

    // 下拉文案 → 发给后端的值(false 那个位置用 null 表示"不过滤")
    private static final String[] STATUS_VALUES = {null, "IN_STOCK", "OUT", "RESERVED", "SCRAPPED", "TRANSFERRED", "PENDING"};
    private static final String[] EXPIRY_VALUES = {null, "EXPIRING", "EXPIRED"};

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
        spStatus = v.findViewById(R.id.sp_status);
        spExpiry = v.findViewById(R.id.sp_expiry);
        MaterialButton btnSearch = v.findViewById(R.id.btn_search);
        MaterialButton btnRefresh = v.findViewById(R.id.btn_refresh);
        btnStocktake = v.findViewById(R.id.btn_stocktake);

        // 文案与卡片上的状态胶囊**共用同一套资源**(原来两边各写一份中文,切英文会出现一个英文一个中文)。
        // 顺序必须与下面的 STATUS_VALUES / EXPIRY_VALUES 一一对齐。
        bindOptions(spStatus, new String[]{
                getString(R.string.inventory_status_all),
                getString(R.string.inventory_status_in_stock),
                getString(R.string.inventory_status_out),
                getString(R.string.inventory_status_reserved),
                getString(R.string.inventory_status_scrapped),
                getString(R.string.inventory_status_transferred),
                getString(R.string.inventory_status_pending)});
        bindOptions(spExpiry, new String[]{
                getString(R.string.inventory_expiry_all),
                getString(R.string.inventory_expiry_expiring),
                getString(R.string.inventory_expiry_expired)});

        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        // 报废入口只给 ADMIN(后端那条是 ADMIN-only 且不可逆);调拨可逆,人人可用
        adapter = new InventoryAdapter(new InventoryAdapter.Callbacks() {
            @Override public void onTransfer(InventoryItem item) {
                TransferDialog.show(requireContext(), item);
            }
            @Override public void onScrap(InventoryItem item) {
                ScrapDialog.show(requireContext(), item, () -> reload());
            }
        }, "ADMIN".equals(SessionStore.role()));
        list.setAdapter(adapter);

        btnSearch.setOnClickListener(x -> reload());
        btnRefresh.setOnClickListener(x -> {
            etSearch.setText("");
            spStatus.setSelection(0);
            spExpiry.setSelection(0);
            reload();
        });
        // 筛选即时生效,不用再点搜索
        spStatus.setOnItemSelectedListener(new SimpleItemSelected(() -> reload()));
        spExpiry.setOnItemSelectedListener(new SimpleItemSelected(() -> reload()));
        btnStocktake.setOnClickListener(x ->
                startActivity(new Intent(requireContext(), StocktakeListActivity.class)));
    }

    private void bindOptions(Spinner sp, String[] labels) {
        ArrayAdapter<String> ad = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
    }

    /** 只关心"选中了"的监听器(首次填充也会触发一次,重复加载无害) */
    private static final class SimpleItemSelected implements android.widget.AdapterView.OnItemSelectedListener {
        private final Runnable onPicked;
        SimpleItemSelected(Runnable onPicked) { this.onPicked = onPicked; }
        @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { onPicked.run(); }
        @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        String kw = etSearch == null ? "" : etSearch.getText().toString().trim();
        String status = valueOf(STATUS_VALUES, spStatus);
        String expiry = valueOf(EXPIRY_VALUES, spExpiry);
        RepositoryProvider.get().searchInventory(kw, status, expiry, new WmsCallback<List<InventoryItem>>() {
            @Override public void onSuccess(List<InventoryItem> data) {
                if (!isAdded()) return;
                adapter.submit(data);
            }
            @Override public void onError(int code, String msg) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), getString(R.string.msg_load_failed, msg),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 下拉当前选中项对应的后端取值;越界(还没填充)返回 null = 不过滤 */
    private static String valueOf(String[] values, Spinner sp) {
        if (sp == null) return null;
        int pos = sp.getSelectedItemPosition();
        return (pos >= 0 && pos < values.length) ? values[pos] : null;
    }
}