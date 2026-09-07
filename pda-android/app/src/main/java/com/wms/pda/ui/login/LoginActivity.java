package com.wms.pda.ui.login;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.wms.pda.BuildConfig;
import com.wms.pda.R;
import com.wms.pda.data.SessionStore;
import com.wms.pda.model.ApiResponse;
import com.wms.pda.model.LoginRequest;
import com.wms.pda.net.ApiClient;
import com.wms.pda.net.ApiException;
import com.wms.pda.ui.home.HomeActivity;

/** 登录页 - 服务器地址 + 用户名/密码 → JWT → 存 SessionStore → 跳 Home。 */
public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etServer, etUsername, etPassword;
    private Button btnLogin;
    private SessionStore session;
    private ApiClient api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        session = new SessionStore(this);

        etServer = findViewById(R.id.etServer);
        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);

        // 预填服务器地址:优先已保存的自定义地址,否则用构建默认
        String saved = session.getBaseUrl();
        etServer.setText(saved != null ? saved : BuildConfig.API_BASE_URL);

        // 已登录直接跳走
        if (session.getToken() != null) {
            goHome();
            return;
        }

        api = new ApiClient(session);
        btnLogin.setOnClickListener(v -> doLogin());
    }

    private void doLogin() {
        String u = etUsername.getText() == null ? "" : etUsername.getText().toString().trim();
        String p = etPassword.getText() == null ? "" : etPassword.getText().toString();
        if (u.isEmpty()) { Toast.makeText(this, R.string.username_required, Toast.LENGTH_SHORT).show(); return; }
        if (p.isEmpty()) { Toast.makeText(this, R.string.password_required, Toast.LENGTH_SHORT).show(); return; }

        // 保存服务器地址,按需重建 ApiClient
        String raw = etServer.getText() == null ? "" : etServer.getText().toString().trim();
        String newBase = normalizeBaseUrl(raw, BuildConfig.API_BASE_URL);
        String cur = session.getBaseUrl();
        if (!newBase.equals(cur)) {
            session.setBaseUrl(newBase);
            api = new ApiClient(session);   // 重新解析 baseUrl
        }

        btnLogin.setEnabled(false);
        String addr = newBase;
        new Thread(() -> {
            try {
                LoginRequest req = new LoginRequest(u, p);
                // 后端返回 ApiResponse<LoginData>,我们用 Map 中转避开 DTO 嵌套
                ApiResponse<java.util.Map<String, Object>> resp =
                    api.post("/api/auth/login", req,
                        (Class) java.util.HashMap.class);
                if (resp == null || resp.data == null) {
                    throw new ApiException(ApiException.ERR_PARSE, "登录响应为空");
                }
                String token = (String) resp.data.get("token");
                String username = (String) resp.data.get("username");
                String role = (String) resp.data.get("role");
                session.setToken(token);
                session.setUsername(username);
                session.setRole(role);
                runOnUiThread(this::goHome);
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    btnLogin.setEnabled(true);
                    Toast.makeText(LoginActivity.this,
                        getString(R.string.login_failed) + ": " + e.getMessage() + " (" + addr + ")",
                        Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * 把用户输入的地址规范成完整 baseUrl(形如 http://192.168.1.100:8080)。
     * 支持裸 IP、IP:端口、带 http:// 的完整地址。输入为空时回落到 defaultBase。
     */
    static String normalizeBaseUrl(String raw, String defaultBase) {
        if (raw == null || raw.trim().isEmpty()) {
            return defaultBase;
        }
        String s = raw.trim();
        if (!s.contains("://")) {
            s = "http://" + s;
        }
        // 去掉末尾斜杠,保证路径拼接干净
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private void goHome() {
        startActivity(new Intent(this, HomeActivity.class));
        finish();
    }
}
