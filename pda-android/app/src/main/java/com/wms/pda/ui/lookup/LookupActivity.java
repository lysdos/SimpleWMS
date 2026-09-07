package com.wms.pda.ui.lookup;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.wms.pda.R;
import com.wms.pda.data.SessionStore;
import com.wms.pda.model.ApiResponse;
import com.wms.pda.model.InventoryItemDto;
import com.wms.pda.net.ApiClient;
import com.wms.pda.net.ApiException;

/** 扫码查询:输入或扫码 → 查 inventory_item → 显示详情。 */
public class LookupActivity extends AppCompatActivity {

    private TextInputEditText etCode;
    private TextView tvResult;
    private ApiClient api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lookup);
        api = new ApiClient(new SessionStore(this));
        etCode = findViewById(R.id.etCode);
        tvResult = findViewById(R.id.tvResult);
        findViewById(R.id.btnQuery).setOnClickListener(v -> doQuery());
        etCode.setOnEditorActionListener((v, id, e) -> { doQuery(); return true; });
    }

    private void doQuery() {
        String code = etCode.getText() == null ? "" : etCode.getText().toString().trim();
        if (code.isEmpty()) return;
        etCode.setText("");
        new Thread(() -> {
            try {
                ApiResponse<InventoryItemDto> resp =
                    api.get("/api/inventory/items/by-code/" + code, InventoryItemDto.class);
                if (resp == null || resp.data == null) {
                    throw new ApiException(404, "编码不存在: " + code);
                }
                InventoryItemDto it = resp.data;
                StringBuilder sb = new StringBuilder();
                sb.append("编码:     ").append(it.code).append('\n');
                sb.append("SKU:      ").append(it.sku).append('\n');
                sb.append("商品:     ").append(it.productName).append('\n');
                sb.append("库位:     ").append(it.locationCode).append('\n');
                sb.append("数量:     ").append(it.quantity).append(" (剩 ").append(it.remaining).append(")\n");
                sb.append("状态:     ").append(statusLabel(it.status)).append('\n');
                sb.append("入库单:   ").append(it.inboundOrderId == null ? "-" : it.inboundOrderId).append('\n');
                sb.append("出库单:   ").append(it.outboundOrderId == null ? "-" : it.outboundOrderId).append('\n');
                sb.append("创建人:   ").append(it.createdBy == null ? "-" : it.createdBy).append('\n');
                sb.append("创建时间: ").append(it.createdAt == null ? "-" : it.createdAt).append('\n');
                runOnUiThread(() -> tvResult.setText(sb.toString()));
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    tvResult.setText("");
                });
            }
        }).start();
    }

    private static String statusLabel(String s) {
        if (s == null) return "-";
        switch (s) {
            case "IN_STOCK":    return "在库";
            case "OUT":         return "已出库";
            case "SCRAPPED":    return "已报废";
            case "TRANSFERRED": return "已调拨";
            default: return s;
        }
    }
}