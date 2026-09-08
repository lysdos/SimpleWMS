package com.wms.pda.ui.outbound;

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
import com.wms.pda.data.model.OutboundOrder;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;
import com.wms.pda.ui.common.ScanSimulator;
import com.wms.pda.ui.common.StatusPill;

import java.util.List;

/** 出库单详情:扫码累计 / 完成 */
public class OutboundDetailActivity extends AppCompatActivity {

    private String orderId;
    private LinearLayout llLines;
    private MaterialButton btnScan, btnComplete;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_outbound_detail);

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        orderId = getIntent().getStringExtra("orderId");
        llLines = findViewById(R.id.ll_lines);
        btnScan = findViewById(R.id.btn_scan);
        btnComplete = findViewById(R.id.btn_complete);

        btnScan.setOnClickListener(v -> ScanSimulator.show(this, code ->
                RepositoryProvider.get().submitOutboundScan(orderId, code, new WmsCallback<OutboundOrder>() {
                    @Override public void onSuccess(OutboundOrder o) {
                        Toast.makeText(OutboundDetailActivity.this,
                                getString(R.string.scanned_out_fmt, code), Toast.LENGTH_SHORT).show();
                        render();
                    }
                    @Override public void onError(int code, String msg) {
                        Toast.makeText(OutboundDetailActivity.this,
                                "扫码失败: " + msg, Toast.LENGTH_SHORT).show();
                    }
                })));

        btnComplete.setOnClickListener(v ->
                RepositoryProvider.get().commitOutbound(orderId, new WmsCallback<OutboundOrder>() {
                    @Override public void onSuccess(OutboundOrder o) {
                        Toast.makeText(OutboundDetailActivity.this, R.string.msg_submitted, Toast.LENGTH_SHORT).show();
                        finish();
                    }
                    @Override public void onError(int code, String msg) {
                        Toast.makeText(OutboundDetailActivity.this, "提交失败: " + msg, Toast.LENGTH_SHORT).show();
                    }
                }));
    }

    @Override
    protected void onResume() { super.onResume(); render(); }

    private void render() {
        RepositoryProvider.get().listOutbound(false, new WmsCallback<List<OutboundOrder>>() {
            @Override public void onSuccess(List<OutboundOrder> orders) {
                OutboundOrder o = null;
                for (OutboundOrder x : orders) if (x.id.equals(orderId)) { o = x; break; }
                if (o == null) { finish(); return; }
                doRender(o);
            }
            @Override public void onError(int code, String msg) {
                Toast.makeText(OutboundDetailActivity.this, "加载失败: " + msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void doRender(OutboundOrder o) {
        ((TextView) findViewById(R.id.tv_id)).setText(o.id);
        StatusPill.apply((TextView) findViewById(R.id.tv_status), o.status);
        ((TextView) findViewById(R.id.tv_customer)).setText(getString(R.string.customer_prefix_fmt, o.customer == null ? "-" : o.customer));
        ((TextView) findViewById(R.id.tv_warehouse)).setText(getString(R.string.outbound_warehouse_prefix_fmt, o.warehouse == null ? "-" : o.warehouse));
        ((TextView) findViewById(R.id.tv_remark)).setText(
                o.remark == null || o.remark.isEmpty() ? getString(R.string.remark_empty) : getString(R.string.remark_prefix_fmt, o.remark));

        llLines.removeAllViews();
        LayoutInflater li = LayoutInflater.from(this);
        if (o.lines == null || o.lines.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.lines_empty_outbound);
            empty.setTextColor(0xFF9AA3B2);
            empty.setPadding(24, 24, 24, 24);
            llLines.addView(empty);
        } else {
            for (OutboundOrder.Line l : o.lines) {
                View row = li.inflate(R.layout.item_inbound_line, llLines, false);
                ((TextView) row.findViewById(R.id.tv_sku_name)).setText(l.sku + "  " + l.name);
                ((TextView) row.findViewById(R.id.tv_barcode)).setText(getString(R.string.qty_fmt, l.qty, l.uom));
                ((TextView) row.findViewById(R.id.tv_qty)).setText("");
                llLines.addView(row);
            }
        }

        boolean done = o.status == Enums.DocStatus.DONE || o.status == Enums.DocStatus.CANCELLED;
        btnScan.setEnabled(!done);
        btnComplete.setEnabled(!done);
    }
}