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
 * 盘点单详情:扫码定位行 / 提交。
 * 接后端模式:用户扫码 → POST /api/stock-checks/{id}/scan → 后端 countedQty += 1,自动算 diff。
 */
public class StocktakeDetailActivity extends AppCompatActivity {

    private String orderId;
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

        orderId = getIntent().getStringExtra("orderId");
        llLines = findViewById(R.id.ll_lines);
        tvProgress = findViewById(R.id.tv_progress);
        btnScan = findViewById(R.id.btn_scan);
        btnSubmit = findViewById(R.id.btn_submit);

        btnScan.setOnClickListener(v -> ScanSimulator.show(this, code ->
                RepositoryProvider.get().submitStocktakeScan(orderId, code, new WmsCallback<StocktakeOrder>() {
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
        // 简化:不校验未盘行(后端允许部分未盘),直接二次确认
        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_complete_stocktake)
                .setMessage(R.string.stocktake_submit_confirm_all)
                .setPositiveButton(R.string.action_confirm, (d, w) ->
                        RepositoryProvider.get().commitStocktake(orderId, new WmsCallback<StocktakeOrder>() {
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
        RepositoryProvider.get().listStocktake(false, new WmsCallback<List<StocktakeOrder>>() {
            @Override public void onSuccess(List<StocktakeOrder> all) {
                StocktakeOrder o = null;
                for (StocktakeOrder x : all) if (x.id.equals(orderId)) { o = x; break; }
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
        TextView tvWh = findViewById(R.id.tv_warehouse);
        TextView tvMode = findViewById(R.id.tv_mode);
        TextView tvCreated = findViewById(R.id.tv_created);

        tvId.setText(o.id);
        StatusPill.apply(tvStatus, o.status);
        tvWh.setText("盘点仓库 · " + (o.warehouse == null ? "-" : o.warehouse));
        tvMode.setText("盘点方式 · " + (o.mode == null ? "-" : o.mode.label));
        tvCreated.setText(getString(R.string.created_at_fmt, o.createdAt == null ? "-" : o.createdAt));

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

                tvSku.setText(l.sku);
                tvSub.setText(getString(R.string.stocktake_line_fmt, l.name, l.barcode, l.location == null ? "-" : l.location));
                tvBook.setText(String.valueOf(l.bookQty));
                etActual.setText(l.actualQty < 0 ? "" : String.valueOf(l.actualQty));
                etActual.setEnabled(false);  // 后端模式下不支持手填(需要 itemId),用扫码

                if (l.actualQty < 0) {
                    tvDiff.setText("—");
                    tvDiff.setTextColor(0xFF9AA3B2);
                    tvPill.setText(R.string.stocktake_status_uncounted);
                    tvPill.setBackgroundResource(R.drawable.bg_pill_draft);
                } else {
                    int d = l.actualQty - l.bookQty;
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

        int total = o.lines == null ? 0 : o.lines.size();
        int counted = 0;
        if (o.lines != null) for (StocktakeOrder.Line l : o.lines) if (l.actualQty >= 0) counted++;
        tvProgress.setText(getString(R.string.stocktake_progress_fmt, counted, total));
    }
}