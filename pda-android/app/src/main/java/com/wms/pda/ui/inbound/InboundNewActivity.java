package com.wms.pda.ui.inbound;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.wms.pda.R;
import com.wms.pda.data.model.InboundOrder;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.WmsCallback;

/** 新建入库单:外部单号 + 备注(后端真正入库的字段) */
public class InboundNewActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inbound_new);

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        EditText etExternalNo = findViewById(R.id.et_external_no);
        EditText etRemark = findViewById(R.id.et_remark);
        MaterialButton submit = findViewById(R.id.btn_submit);

        submit.setOnClickListener(v -> {
            String externalNo = etExternalNo.getText().toString().trim();
            String remark = etRemark.getText().toString().trim();
            submit.setEnabled(false);
            RepositoryProvider.get().createInbound(externalNo, null, remark, new WmsCallback<InboundOrder>() {
                @Override public void onSuccess(InboundOrder order) {
                    Toast.makeText(InboundNewActivity.this,
                            getString(R.string.msg_created_fmt, order.id), Toast.LENGTH_SHORT).show();
                    finish();
                }
                @Override public void onError(int code, String msg) {
                    submit.setEnabled(true);
                    Toast.makeText(InboundNewActivity.this,
                            getString(R.string.msg_create_failed, msg), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }
}