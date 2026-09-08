package com.wms.pda.ui.login;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import android.widget.ProgressBar;
import com.wms.pda.R;
import com.wms.pda.data.api.ApiClient;
import com.wms.pda.data.remote.RemoteWmsRepository;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.SessionStore;
import com.wms.pda.data.repo.SettingsStore;
import com.wms.pda.ui.main.MainActivity;

/**
 * 登录页。右上齿轮跳服务器设置。
 * 接后端:POST /api/auth/login → 保存 token + 切到 RemoteWmsRepository。
 */
public class LoginActivity extends AppCompatActivity {

    private EditText etUser, etPwd;
    private MaterialButton btnLogin;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        TextView tvServer = findViewById(R.id.tv_server_url);
        etUser = findViewById(R.id.et_username);
        etPwd = findViewById(R.id.et_password);
        btnLogin = findViewById(R.id.btn_login);
        progress = findViewById(R.id.progress);
        ImageButton btnSettings = findViewById(R.id.btn_settings);

        tvServer.setText(SettingsStore.getBaseUrl());

        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        btnLogin.setOnClickListener(v -> doLogin());

        // 已登录直接进
        if (SessionStore.isLoggedIn()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        TextView tvServer = findViewById(R.id.tv_server_url);
        tvServer.setText(SettingsStore.getBaseUrl());
    }

    private void doLogin() {
        String u = etUser.getText().toString().trim();
        String p = etPwd.getText().toString().trim();
        if (u.isEmpty()) { etUser.setError("请输入账号"); return; }
        if (p.isEmpty()) { etPwd.setError("请输入密码"); return; }

        setLoading(true);
        ApiClient api = new ApiClient();
        api.refreshBaseUrl();
        RemoteWmsRepository remote = new RemoteWmsRepository(api);
        RepositoryProvider.setRepository(remote);

        remote.login(u, p, new com.wms.pda.data.repo.WmsCallback<String>() {
            @Override public void onSuccess(String token) {
                String role = remote.getLastLoginRole();
                SessionStore.login(u, token, role);
                setLoading(false);
                startActivity(new Intent(LoginActivity.this, MainActivity.class));
                finish();
            }
            @Override public void onError(int code, String msg) {
                setLoading(false);
                etPwd.setError("登录失败: " + msg);
                // 登录失败 → 回退 Mock,避免主页面用 stale remote 调接口全失败
                RepositoryProvider.resetToMock();
                SessionStore.logout();
            }
        });
    }

    private void setLoading(boolean loading) {
        btnLogin.setEnabled(!loading);
        etUser.setEnabled(!loading);
        etPwd.setEnabled(!loading);
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
    }
}