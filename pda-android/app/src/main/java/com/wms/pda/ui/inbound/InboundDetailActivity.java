package com.wms.pda.ui.inbound;

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

/** 入库单详情:展示后端实际有的字段;主单 + 明细;添加明细;提交(后端生成 24 位码)。 */
public class InboundDetailActivity extends AppCompatActivity {

    private Integer dbId;          // 后端数字 id(详情接口 / commit 接口都需要)
    private String orderNo;         // 展示用单号
    private LinearLayout llLines;
    private MaterialButton btnComplete;
    private MaterialButton btnAddItem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inbound_detail);

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        dbId = getIntent().getIntExtra("orderDbId", -1);
        orderNo = getIntent().getStringExtra("orderId");
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
        AddInboundItemDialog.show(this, dbId, this::render);
    }

    private void confirmDeleteLine(InboundOrder.InboundLine l) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(R.string.confirm_delete_line)
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    RepositoryProvider.get().deleteInboundItem(String.valueOf(dbId), String.valueOf(l.id),
                            new WmsCallback<InboundOrder>() {
                                @Override public void onSuccess(InboundOrder o) {
                                    Toast.makeText(InboundDetailActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                                    render();
                                }
                                @Override public void onError(int code, String msg) {
                                    Toast.makeText(InboundDetailActivity.this, "删除失败: " + msg, Toast.LENGTH_SHORT).show();
                                }
                            });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void doCommit() {
        if (dbId == null || dbId < 0) { return; }
        btnComplete.setEnabled(false);
        RepositoryProvider.get().commitInbound(String.valueOf(dbId), new WmsCallback<InboundOrder>() {
            @Override public void onSuccess(InboundOrder o) {
                btnComplete.setEnabled(true);
                Toast.makeText(InboundDetailActivity.this, R.string.msg_submitted, Toast.LENGTH_SHORT).show();
                render();
            }
            @Override public void onError(int code, String msg) {
                btnComplete.setEnabled(true);
                Toast.makeText(InboundDetailActivity.this, "提交失败: " + msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void render() {
        if (dbId == null || dbId < 0) { finish(); return; }
        RepositoryProvider.get().getInboundOrder(String.valueOf(dbId), new WmsCallback<InboundOrder>() {
            @Override public void onSuccess(InboundOrder o) {
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

        TextView tvExternal = findViewById(R.id.tv_external_no);
        tvExternal.setText(o.externalNo == null || o.externalNo.isEmpty()
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
            empty.setText(R.string.lines_empty_inbound);
            empty.setTextColor(0xFF9AA3B2);
            empty.setPadding(24, 24, 24, 24);
            llLines.addView(empty);
        } else {
            boolean draft = o.status == Enums.DocStatus.DRAFT;
            for (InboundOrder.InboundLine l : o.lines) {
                View row = li.inflate(R.layout.item_inbound_line, llLines, false);
                TextView tvSkuName = row.findViewById(R.id.tv_sku_name);
                TextView tvInfo    = row.findViewById(R.id.tv_barcode);
                TextView tvQty     = row.findViewById(R.id.tv_qty);

                tvSkuName.setText((l.sku == null ? "?" : l.sku) + "  " + (l.name == null ? "" : l.name));
                tvQty.setText("× " + l.qty + " " + (l.uom == null ? "" : l.uom));

                StringBuilder info = new StringBuilder();
                if (l.location != null && !l.location.isEmpty()) info.append("库位: ").append(l.location).append("  ");
                if (l.supplier != null && !l.supplier.isEmpty()) info.append("供应商: ").append(l.supplier).append("  ");
                if (l.unitPrice != null && !l.unitPrice.isEmpty()) info.append("单价: ¥").append(l.unitPrice).append("  ");
                if (l.rowRemark != null && !l.rowRemark.isEmpty()) info.append("备注: ").append(l.rowRemark);
                if (l.codes != null && !l.codes.isEmpty()) {
                    info.append("\n24 位码: ").append(l.codes.size()).append(" 个");
                    // 仅展示前 3 个,过长折叠
                    int shown = Math.min(3, l.codes.size());
                    for (int i = 0; i < shown; i++) info.append("\n  • ").append(l.codes.get(i));
                    if (l.codes.size() > 3) info.append("\n  • …(+").append(l.codes.size() - 3).append(")");
                }
                tvInfo.setText(info.toString());

                View actions = row.findViewById(R.id.ll_line_actions);
                if (draft) {
                    actions.setVisibility(View.VISIBLE);
                    row.findViewById(R.id.btn_line_edit).setOnClickListener(v ->
                            AddInboundItemDialog.showEdit(this, dbId, l, this::render));
                    row.findViewById(R.id.btn_line_delete).setOnClickListener(v -> confirmDeleteLine(l));
                } else {
                    actions.setVisibility(View.GONE);
                }
                llLines.addView(row);
            }
        }

        boolean done = o.status == Enums.DocStatus.DONE || o.status == Enums.DocStatus.CANCELLED;
        btnAddItem.setEnabled(!done);
        btnComplete.setEnabled(!done);
    }
}