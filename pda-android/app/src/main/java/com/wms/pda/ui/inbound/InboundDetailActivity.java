package com.wms.pda.ui.inbound;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.wms.pda.R;
import com.wms.pda.data.model.Enums;
import com.wms.pda.data.model.InboundOrder;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;
import com.wms.pda.ui.common.StatusPill;

import java.util.List;

/** 入库单详情:显示主单 + 明细,提交入库(后端生成 24 位码)。 */
public class InboundDetailActivity extends AppCompatActivity {

    private String orderId;
    private LinearLayout llLines;
    private MaterialButton btnComplete;
    private MaterialButton btnAddItem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inbound_detail);

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        orderId = getIntent().getStringExtra("orderId");
        llLines = findViewById(R.id.ll_lines);
        btnComplete = findViewById(R.id.btn_complete);
        btnAddItem = findViewById(R.id.btn_scan);

        btnAddItem.setText(R.string.inbound_add_item_action);
        btnAddItem.setOnClickListener(v -> openAddItem());

        btnComplete.setOnClickListener(v -> doCommit());
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void openAddItem() {
        // 拉取库存列表,作为"选商品"源
        RepositoryProvider.get().searchInventory("", new WmsCallback<List<com.wms.pda.data.model.InventoryItem>>() {
            @Override public void onSuccess(List<com.wms.pda.data.model.InventoryItem> items) {
                if (items == null || items.isEmpty()) {
                    Toast.makeText(InboundDetailActivity.this, "请先在 PC 端维护商品", Toast.LENGTH_SHORT).show();
                    return;
                }
                AddInboundItemDialog.show(InboundDetailActivity.this, items, orderId, () -> render());
            }
            @Override public void onError(int code, String msg) {
                Toast.makeText(InboundDetailActivity.this, "加载商品失败: " + msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void doCommit() {
        RepositoryProvider.get().commitInbound(orderId, new WmsCallback<InboundOrder>() {
            @Override public void onSuccess(InboundOrder o) {
                Toast.makeText(InboundDetailActivity.this, R.string.msg_submitted, Toast.LENGTH_SHORT).show();
                render();
            }
            @Override public void onError(int code, String msg) {
                Toast.makeText(InboundDetailActivity.this, "提交失败: " + msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void render() {
        RepositoryProvider.get().listInbound(false, new WmsCallback<List<InboundOrder>>() {
            @Override public void onSuccess(List<InboundOrder> orders) {
                InboundOrder o = null;
                for (InboundOrder x : orders) if (x.id.equals(orderId)) { o = x; break; }
                if (o == null) { finish(); return; }
                doRender(o);
            }
            @Override public void onError(int code, String msg) {
                Toast.makeText(InboundDetailActivity.this, "加载失败: " + msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void doRender(InboundOrder o) {
        ((TextView) findViewById(R.id.tv_id)).setText(o.id);
        StatusPill.apply((TextView) findViewById(R.id.tv_status), o.status);
        ((TextView) findViewById(R.id.tv_supplier)).setText(getString(R.string.supplier_prefix_fmt, o.supplier == null ? "-" : o.supplier));
        ((TextView) findViewById(R.id.tv_warehouse)).setText(getString(R.string.warehouse_prefix_fmt, o.warehouse == null ? "-" : o.warehouse));
        ((TextView) findViewById(R.id.tv_remark)).setText(
                o.remark == null || o.remark.isEmpty() ? getString(R.string.remark_empty) : getString(R.string.remark_prefix_fmt, o.remark));

        llLines.removeAllViews();
        LayoutInflater li = LayoutInflater.from(this);
        if (o.lines == null || o.lines.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.lines_empty_inbound);
            empty.setTextColor(0xFF9AA3B2);
            empty.setPadding(24, 24, 24, 24);
            llLines.addView(empty);
        } else {
            for (InboundOrder.InboundLine l : o.lines) {
                View row = li.inflate(R.layout.item_inbound_line, llLines, false);
                ((TextView) row.findViewById(R.id.tv_sku_name)).setText(l.sku + "  " + l.name);
                ((TextView) row.findViewById(R.id.tv_barcode)).setText(getString(R.string.qty_fmt, l.qty, l.uom));
                ((TextView) row.findViewById(R.id.tv_qty)).setText("");
                llLines.addView(row);
            }
        }

        boolean done = o.status == Enums.DocStatus.DONE || o.status == Enums.DocStatus.CANCELLED;
        btnAddItem.setEnabled(!done);
        btnComplete.setEnabled(!done);
    }
}