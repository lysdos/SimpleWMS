package com.wms.pda.ui.stocktake;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.wms.pda.R;
import com.wms.pda.data.model.Enums;
import com.wms.pda.data.model.StocktakeOrder;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;
import com.wms.pda.ui.common.ScanSimulator;
import com.wms.pda.ui.common.StatusPill;

import java.util.List;

/**
 * 盘点单详情:对接后端 /api/stock-checks/{id}。扫码定位行 / 提交。
 */
public class StocktakeDetailActivity extends AppCompatActivity {

    private Integer dbId;
    private LinearLayout llLines;
    private TextView tvProgress;
    private MaterialButton btnScan;
    private MaterialButton btnSubmit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stocktake_detail);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        dbId = getIntent().getIntExtra("orderDbId", -1);
        llLines = findViewById(R.id.ll_lines);
        tvProgress = findViewById(R.id.tv_progress);
        btnScan = findViewById(R.id.btn_scan);
        btnSubmit = findViewById(R.id.btn_submit);

        btnScan.setOnClickListener(v -> ScanSimulator.show(this, code ->
                RepositoryProvider.get().submitStocktakeScan(String.valueOf(dbId), code,
                        new WmsCallback<StocktakeOrder>() {
                            @Override public void onSuccess(StocktakeOrder o) {
                                Toast.makeText(StocktakeDetailActivity.this,
                                        getString(R.string.stocktake_located_fmt, code), Toast.LENGTH_SHORT).show();
                                render();
                            }
                            @Override public void onError(int code, String msg) {
                                Toast.makeText(StocktakeDetailActivity.this,
                                        "扫码失败: " + msg, Toast.LENGTH_SHORT).show();
                            }
                        })));

        btnSubmit.setOnClickListener(v -> confirmAndSubmit());
    }

    @Override
    protected void onResume() { super.onResume(); render(); }

    private void confirmAndSubmit() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_complete_stocktake)
                .setMessage(R.string.stocktake_submit_confirm_all)
                .setPositiveButton(R.string.action_confirm, (d, w) ->
                        RepositoryProvider.get().commitStocktake(String.valueOf(dbId),
                                new WmsCallback<StocktakeOrder>() {
                                    @Override public void onSuccess(StocktakeOrder o) {
                                        Toast.makeText(StocktakeDetailActivity.this, R.string.msg_submitted, Toast.LENGTH_SHORT).show();
                                        finish();
                                    }
                                    @Override public void onError(int code, String msg) {
                                        Toast.makeText(StocktakeDetailActivity.this, "提交失败: " + msg, Toast.LENGTH_SHORT).show();
                                    }
                                }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void render() {
        if (dbId == null || dbId < 0) { finish(); return; }
        RepositoryProvider.get().getStocktake(String.valueOf(dbId),
                new WmsCallback<StocktakeOrder>() {
                    @Override public void onSuccess(StocktakeOrder o) {
                        if (o == null) { finish(); return; }
                        doRender(o);
                    }
                    @Override public void onError(int code, String msg) {
                        Toast.makeText(StocktakeDetailActivity.this, "加载失败: " + msg, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void doRender(StocktakeOrder o) {
        TextView tvId = findViewById(R.id.tv_id);
        TextView tvStatus = findViewById(R.id.tv_status);
        TextView tvScope = findViewById(R.id.tv_warehouse);
        TextView tvMode = findViewById(R.id.tv_mode);
        TextView tvOperator = findViewById(R.id.tv_operator);
        TextView tvCreated = findViewById(R.id.tv_created);

        tvId.setText(o.id);
        StatusPill.apply(tvStatus, o.status);
        String kw = (o.scopeKeyword == null || o.scopeKeyword.isEmpty()) ? "—" : o.scopeKeyword;
        tvScope.setText("盘点范围: " + kw);
        String scopeLabel = "ALL".equals(o.scope) ? "全盘"
                : "SKU".equals(o.scope) ? "抽样盘"
                : "LOCATION".equals(o.scope) ? "动碰盘"
                : (o.scope == null ? "—" : o.scope);
        tvMode.setText("盘点方式: " + scopeLabel);
        tvOperator.setText("操作员: " + (o.operator == null ? "—" : o.operator));
        tvCreated.setText(getString(R.string.created_at_fmt, o.createdAt == null ? "—" : o.createdAt));

        llLines.removeAllViews();
        LayoutInflater li = LayoutInflater.from(this);

        if (o.lines == null || o.lines.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("盘点单无明细");
            empty.setTextColor(0xFF9AA3B2);
            empty.setPadding(24, 24, 24, 24);
            llLines.addView(empty);
        } else {
            for (StocktakeOrder.Line l : o.lines) {
                View row = li.inflate(R.layout.item_stocktake_row, llLines, false);
                TextView tvSku = row.findViewById(R.id.tv_sku);
                TextView tvSub = row.findViewById(R.id.tv_sub);
                TextView tvBook = row.findViewById(R.id.tv_book);
                TextView etActual = row.findViewById(R.id.et_actual);
                TextView tvDiff = row.findViewById(R.id.tv_diff);
                TextView tvPill = row.findViewById(R.id.tv_pill);

                tvSku.setText(l.sku == null ? "?" : l.sku);
                tvSub.setText((l.productName == null ? "" : l.productName)
                        + " · 码 " + (l.code == null ? "—" : l.code)
                        + " · " + (l.locationCode == null ? "—" : l.locationCode));
                tvBook.setText(l.systemQty == null ? "—" : String.valueOf(l.systemQty));
                etActual.setText(l.countedQty == null ? "" : String.valueOf(l.countedQty));
                etActual.setEnabled(false);

                int sys = l.systemQty == null ? 0 : l.systemQty;
                int cnt = l.countedQty == null ? -1 : l.countedQty;
                if (cnt < 0) {
                    tvDiff.setText("—");
                    tvDiff.setTextColor(0xFF9AA3B2);
                    tvPill.setText(R.string.stocktake_status_uncounted);
                    tvPill.setBackgroundResource(R.drawable.bg_pill_draft);
                } else {
                    int d = cnt - sys;
                    String sign = d > 0 ? "+" : "";
                    tvDiff.setText(sign + d);
                    if (d == 0) {
                        tvDiff.setTextColor(0xFF27AE60);
                        tvPill.setText(R.string.stocktake_status_consistent);
                        tvPill.setBackgroundResource(R.drawable.bg_pill_success);
                    } else if (d > 0) {
                        tvDiff.setTextColor(0xFF27AE60);
                        tvPill.setText(R.string.stocktake_status_over);
                        tvPill.setBackgroundResource(R.drawable.bg_pill_success);
                    } else {
                        tvDiff.setTextColor(0xFFE74C3C);
                        tvPill.setText(R.string.stocktake_status_short);
                        tvPill.setBackgroundResource(R.drawable.bg_pill_warning);
                    }
                }
                llLines.addView(row);
            }
        }

        boolean done = o.status == Enums.DocStatus.DONE || o.status == Enums.DocStatus.CANCELLED;
        btnScan.setEnabled(!done);
        btnSubmit.setEnabled(!done);

        int total = o.totalCount == null ? 0 : o.totalCount;
        int counted = o.countedCount == null ? 0 : o.countedCount;
        tvProgress.setText(getString(R.string.stocktake_progress_fmt, counted, total));
    }
}