package com.wms.pda.ui.outbound;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.wms.pda.R;
import com.wms.pda.data.model.OutboundOrder;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;

/** 新建出库单 */
public class OutboundNewActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_outbound_new);

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        EditText etCust = findViewById(R.id.et_customer);
        EditText etRemark = findViewById(R.id.et_remark);
        Spinner sp = findViewById(R.id.sp_warehouse);
        MaterialButton submit = findViewById(R.id.btn_submit);

        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"主仓", "分仓"});
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);

        submit.setOnClickListener(v -> {
            String cust = etCust.getText().toString().trim();
            if (cust.isEmpty()) { etCust.setError("请填写客户/部门"); return; }
            String wh = (String) sp.getSelectedItem();
            String remark = etRemark.getText().toString().trim();
            submit.setEnabled(false);
            RepositoryProvider.get().createOutbound(cust, wh, remark, new WmsCallback<OutboundOrder>() {
                @Override public void onSuccess(OutboundOrder o) {
                    Toast.makeText(OutboundNewActivity.this, "已创建: " + o.id, Toast.LENGTH_SHORT).show();
                    finish();
                }
                @Override public void onError(int code, String msg) {
                    submit.setEnabled(true);
                    Toast.makeText(OutboundNewActivity.this, "创建失败: " + msg, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }
}