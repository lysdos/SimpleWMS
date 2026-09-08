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

/** 出库单详情:展示后端实际字段;扫码累计 / 提交。 */
public class OutboundDetailActivity extends AppCompatActivity {

    private Integer dbId;
    private LinearLayout llLines;
    private MaterialButton btnScan, btnComplete;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_outbound_detail);

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        dbId = getIntent().getIntExtra("orderDbId", -1);
        llLines = findViewById(R.id.ll_lines);
        btnScan = findViewById(R.id.btn_scan);
        btnComplete = findViewById(R.id.btn_complete);

        btnScan.setOnClickListener(v -> ScanSimulator.show(this, code ->
                RepositoryProvider.get().submitOutboundScan(String.valueOf(dbId), code,
                        new WmsCallback<OutboundOrder>() {
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
                RepositoryProvider.get().commitOutbound(String.valueOf(dbId),
                        new WmsCallback<OutboundOrder>() {
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
        if (dbId == null || dbId < 0) { finish(); return; }
        RepositoryProvider.get().getOutboundOrder(String.valueOf(dbId),
                new WmsCallback<OutboundOrder>() {
                    @Override public void onSuccess(OutboundOrder o) {
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

        TextView tvCustomer = findViewById(R.id.tv_customer);
        tvCustomer.setText(o.customer == null || o.customer.isEmpty()
                ? "客户/部门: —" : "客户/部门: " + o.customer);

        TextView tvExternalNo = findViewById(R.id.tv_external_no);
        tvExternalNo.setText(o.externalNo == null || o.externalNo.isEmpty()
                ? "外部单号: —" : "外部单号: " + o.externalNo);

        TextView tvOperator = findViewById(R.id.tv_operator);
        tvOperator.setText(o.operator == null ? "操作员: —" : "操作员: " + o.operator);

        TextView tvRemark = findViewById(R.id.tv_remark);
        tvRemark.setText(o.remark == null || o.remark.isEmpty()
                ? getString(R.string.remark_empty) : getString(R.string.remark_prefix_fmt, o.remark));

        TextView tvSummary = findViewById(R.id.tv_summary);
        int itemCount = o.itemCount == null ? 0 : o.itemCount;
        int totalQty  = o.totalQuantity == null ? 0 : o.totalQuantity;
        tvSummary.setText(itemCount + " 行 · " + totalQty + " 件 · 创建于 " +
                (o.createdAt == null ? "—" : (o.createdAt.length() > 16 ? o.createdAt.substring(0, 16).replace("T", " ") : o.createdAt)));

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
                TextView tvSkuName = row.findViewById(R.id.tv_sku_name);
                TextView tvInfo    = row.findViewById(R.id.tv_barcode);
                TextView tvQty     = row.findViewById(R.id.tv_qty);

                tvSkuName.setText((l.sku == null ? "?" : l.sku) + "  " + (l.name == null ? "" : l.name));
                tvQty.setText("× " + l.qty + " " + (l.uom == null ? "" : l.uom));

                StringBuilder info = new StringBuilder();
                if (l.location != null && !l.location.isEmpty()) info.append("库位: ").append(l.location).append("  ");
                if (l.unitPrice != null && !l.unitPrice.isEmpty()) info.append("单价: ¥").append(l.unitPrice).append("  ");
                if (l.rowRemark != null && !l.rowRemark.isEmpty()) info.append("备注: ").append(l.rowRemark);
                tvInfo.setText(info.toString());
                llLines.addView(row);
            }
        }

        boolean done = o.status == Enums.DocStatus.DONE || o.status == Enums.DocStatus.CANCELLED;
        btnScan.setEnabled(!done);
        btnComplete.setEnabled(!done);
    }
}