package com.wms.pda.ui.outbound;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.wms.pda.R;
import com.wms.pda.data.model.OutboundOrder;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;

/** 新建出库单:客户 + 外部单号 + 备注(后端真正存的字段) */
public class OutboundNewActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_outbound_new);

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        EditText etCust = findViewById(R.id.et_customer);
        EditText etExternalNo = findViewById(R.id.et_external_no);
        EditText etRemark = findViewById(R.id.et_remark);
        MaterialButton submit = findViewById(R.id.btn_submit);

        submit.setOnClickListener(v -> {
            String cust = etCust.getText().toString().trim();
            if (cust.isEmpty()) { etCust.setError(getString(R.string.err_customer_required)); return; }
            String externalNo = etExternalNo.getText().toString().trim();
            String remark = etRemark.getText().toString().trim();
            submit.setEnabled(false);
            // customer 真实传入后端(后端必填);externalNo / remark 可选
            RepositoryProvider.get().createOutbound(cust, externalNo, remark, new WmsCallback<OutboundOrder>() {
                @Override public void onSuccess(OutboundOrder o) {
                    Toast.makeText(OutboundNewActivity.this,
                            getString(R.string.msg_created_fmt, o.id), Toast.LENGTH_SHORT).show();
                    finish();
                }
                @Override public void onError(int code, String msg) {
                    submit.setEnabled(true);
                    Toast.makeText(OutboundNewActivity.this,
                            getString(R.string.msg_create_failed, msg), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }
}