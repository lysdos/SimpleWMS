package com.wms.pda.ui.main;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.wms.pda.R;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.SessionStore;
import com.wms.pda.ui.dashboard.DashboardFragment;
import com.wms.pda.ui.inbound.InboundFragment;
import com.wms.pda.ui.inventory.InventoryFragment;
import com.wms.pda.ui.login.LoginActivity;
import com.wms.pda.ui.login.SettingsActivity;
import com.wms.pda.ui.outbound.OutboundFragment;

/**
 * 主框架:BottomNav 4 个 Tab。
 * 设置 / 退出登录 入口在 toolbar 右上。
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        BottomNavigationView nav = findViewById(R.id.bottom_nav);

        tb.findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        tb.findViewById(R.id.btn_logout).setOnClickListener(v -> {
            SessionStore.logout();
            RepositoryProvider.reset();
            Toast.makeText(this, R.string.msg_logout_done, Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });

        nav.setOnItemSelectedListener(item -> {
            Fragment f;
            int id = item.getItemId();
            if (id == R.id.nav_dashboard) f = new DashboardFragment();
            else if (id == R.id.nav_inbound) f = new InboundFragment();
            else if (id == R.id.nav_outbound) f = new OutboundFragment();
            else f = new InventoryFragment();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container, f)
                    .commit();
            return true;
        });

        if (savedInstanceState == null) {
            nav.setSelectedItemId(R.id.nav_dashboard);
        }
    }
}
