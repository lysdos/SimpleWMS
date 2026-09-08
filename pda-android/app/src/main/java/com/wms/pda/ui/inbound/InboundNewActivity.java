package com.wms.pda.ui.inbound;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.wms.pda.R;
import com.wms.pda.data.model.InboundOrder;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;

/** 新建入库单 */
public class InboundNewActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inbound_new);

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        EditText etSupplier = findViewById(R.id.et_supplier);
        EditText etRemark = findViewById(R.id.et_remark);
        Spinner sp = findViewById(R.id.sp_warehouse);
        MaterialButton submit = findViewById(R.id.btn_submit);

        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"主仓", "分仓"});
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);

        submit.setOnClickListener(v -> {
            String supplier = etSupplier.getText().toString().trim();
            if (supplier.isEmpty()) { etSupplier.setError("请填写供应商"); return; }
            String wh = (String) sp.getSelectedItem();
            String remark = etRemark.getText().toString().trim();
            submit.setEnabled(false);
            RepositoryProvider.get().createInbound(supplier, wh, remark, new WmsCallback<InboundOrder>() {
                @Override public void onSuccess(InboundOrder order) {
                    Toast.makeText(InboundNewActivity.this, "已创建: " + order.id, Toast.LENGTH_SHORT).show();
                    finish();
                }
                @Override public void onError(int code, String msg) {
                    submit.setEnabled(true);
                    Toast.makeText(InboundNewActivity.this, "创建失败: " + msg, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }
}