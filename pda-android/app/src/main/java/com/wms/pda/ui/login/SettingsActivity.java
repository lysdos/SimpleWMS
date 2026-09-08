package com.wms.pda.ui.login;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.wms.pda.R;
import com.wms.pda.data.repo.SettingsStore;

/**
 * 服务器设置:输入 baseUrl,测试连接(原型阶段只校验格式),保存。
 * 进入入口: 登录页右上角齿轮 / 主界面右上角齿轮。
 */
public class SettingsActivity extends AppCompatActivity {

    private EditText et;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        et = findViewById(R.id.et_address);
        et.setText(SettingsStore.getBaseUrl());

        findViewById(R.id.btn_test).setOnClickListener(v -> test());
        findViewById(R.id.btn_reset).setOnClickListener(v -> {
            SettingsStore.reset();
            et.setText(SettingsStore.getBaseUrl());
            Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_save).setOnClickListener(v -> save());
    }

    private void test() {
        String url = et.getText().toString().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, R.string.settings_test_fail, Toast.LENGTH_SHORT).show();
            return;
        }
        // 原型:仅格式校验。真实环境可发 GET /api/ping
        Toast.makeText(this, R.string.settings_test_success, Toast.LENGTH_SHORT).show();
    }

    private void save() {
        String url = et.getText().toString().trim();
        if (url.isEmpty()) {
            et.setError("地址不能为空");
            return;
        }
        SettingsStore.setBaseUrl(url);
        Toast.makeText(this, R.string.msg_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
