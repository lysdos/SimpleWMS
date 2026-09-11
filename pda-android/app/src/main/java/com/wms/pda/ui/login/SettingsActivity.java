package com.wms.pda.ui.login;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.wms.pda.R;
import com.wms.pda.data.api.ApiClient;
import com.wms.pda.data.repo.SettingsStore;

import java.util.Map;

/**
 * 服务器设置:输入 baseUrl,测试连接(真正发请求),保存。
 * 进入入口: 登录页右上角齿轮 / 主界面右上角齿轮。
 */
public class SettingsActivity extends AppCompatActivity {

    private EditText et;
    private MaterialButton btnTest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        et = findViewById(R.id.et_address);
        et.setText(SettingsStore.getBaseUrl());
        btnTest = findViewById(R.id.btn_test);

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
        if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            Toast.makeText(this, R.string.settings_test_fail, Toast.LENGTH_SHORT).show();
            return;
        }

        btnTest.setEnabled(false);
        btnTest.setText("连接中...");

        ApiClient probe = new ApiClient();
        probe.refreshBaseUrl();
        // 发 GET /api/auth/me 验证:
        // - 200(onSuccess): WMS 后端可达且 token 有效
        // - HTTP 非2xx + body 含 "未登录": WMS 后端可达(未登录,正常)
        // - HTTP 非2xx + body 不含 "未登录": 其他服务(非 WMS)
        // - 网络异常(code=-1): 不可达
        probe.get("/api/auth/me", Map.class, new ApiClient.Callback<Map>() {
            @Override
            public void onSuccess(Map value) {
                runOnUiThread(() -> {
                    btnTest.setEnabled(true);
                    btnTest.setText(R.string.settings_test);
                    Toast.makeText(SettingsActivity.this,
                            "连接成功，WMS 后端", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> {
                    btnTest.setEnabled(true);
                    btnTest.setText(R.string.settings_test);
                    if (code == -1) {
                        // 网络异常:超时/连接拒绝/DNS 解析失败
                        Toast.makeText(SettingsActivity.this,
                                "连接失败: " + msg, Toast.LENGTH_SHORT).show();
                    } else if (msg != null && msg.contains("未登录")) {
                        // HTTP 响应体含 "未登录" → 这是 WMS 后端(只是没登录)
                        Toast.makeText(SettingsActivity.this,
                                "连接成功，WMS 后端", Toast.LENGTH_SHORT).show();
                    } else {
                        // 有 HTTP 响应但不是 WMS → 连到了其他服务
                        Toast.makeText(SettingsActivity.this,
                                "已连接，但不是 WMS 后端", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
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
