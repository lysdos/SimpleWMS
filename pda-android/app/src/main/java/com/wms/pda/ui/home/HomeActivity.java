package com.wms.pda.ui.home;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.wms.pda.R;
import com.wms.pda.data.SessionStore;
import com.wms.pda.scan.ScanManager;
import com.wms.pda.ui.check.StockCheckActivity;
import com.wms.pda.ui.inbound.InboundScanActivity;
import com.wms.pda.ui.lookup.LookupActivity;
import com.wms.pda.ui.outbound.OutboundScanActivity;

/** 主页 - 4 个入口 + RFID 状态。 */
public class HomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        SessionStore session = new SessionStore(this);
        TextView tvUser = findViewById(R.id.tvUser);
        TextView tvRfid = findViewById(R.id.tvRfid);
        tvUser.setText("👤 " + (session.getUsername() == null ? "?" : session.getUsername()));

        ScanManager mgr = ScanManager.get(this);
        tvRfid.setText(mgr.isUhfReady() ? R.string.rfid_ready : R.string.rfid_offline);

        findViewById(R.id.btnInbound).setOnClickListener(v ->
            startActivity(new Intent(this, InboundScanActivity.class)));
        findViewById(R.id.btnOutbound).setOnClickListener(v ->
            startActivity(new Intent(this, OutboundScanActivity.class)));
        findViewById(R.id.btnCheck).setOnClickListener(v ->
            startActivity(new Intent(this, StockCheckActivity.class)));
        findViewById(R.id.btnLookup).setOnClickListener(v ->
            startActivity(new Intent(this, LookupActivity.class)));

        findViewById(R.id.btnLogout).setOnClickListener(v -> {
            session.clearAuth();
            startActivity(new Intent(this, com.wms.pda.ui.login.LoginActivity.class));
            finish();
        });
    }
}
