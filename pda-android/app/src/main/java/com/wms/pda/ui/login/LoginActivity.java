package com.wms.pda.ui.login;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.wms.pda.R;
import com.wms.pda.data.SessionStore;
import com.wms.pda.model.ApiResponse;
import com.wms.pda.model.LoginRequest;
import com.wms.pda.model.LoginResponse;
import com.wms.pda.net.ApiClient;
import com.wms.pda.net.ApiException;
import com.wms.pda.ui.home.HomeActivity;

/** 登录页 - 用户名/密码 → JWT → 存 SessionStore → 跳 Home。 */
public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etUsername, etPassword;
    private Button btnLogin;
    private SessionStore session;
    private ApiClient api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        session = new SessionStore(this);
        api = new ApiClient(session);

        // 已登录直接跳走
        if (session.getToken() != null) {
            goHome();
            return;
        }

        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);

        btnLogin.setOnClickListener(v -> doLogin());
    }

    private void doLogin() {
        String u = etUsername.getText() == null ? "" : etUsername.getText().toString().trim();
        String p = etPassword.getText() == null ? "" : etPassword.getText().toString();
        if (u.isEmpty()) { Toast.makeText(this, R.string.username_required, Toast.LENGTH_SHORT).show(); return; }
        if (p.isEmpty()) { Toast.makeText(this, R.string.password_required, Toast.LENGTH_SHORT).show(); return; }

        btnLogin.setEnabled(false);
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
                        getString(R.string.login_failed) + ": " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void goHome() {
        startActivity(new Intent(this, HomeActivity.class));
        finish();
    }
}
