package com.wms.pda.ui.login;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.button.MaterialButton;
import com.wms.pda.R;
import com.wms.pda.data.api.ApiClient;
import com.wms.pda.data.remote.BackendMessages;
import com.wms.pda.data.repo.SettingsStore;

import java.util.Locale;
import java.util.Map;

/**
 * 服务器设置:输入 baseUrl,测试连接(真正发请求),保存;外加界面语言切换。
 * 进入入口: 登录页右上角齿轮 / 主界面右上角齿轮。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String LANG_ZH = "zh-CN";
    private static final String LANG_EN = "en";

    private EditText et;
    private MaterialButton btnTest;
    /** 初始化时程序化 setSelection 也会回调一次 onItemSelected,那一次不算用户操作 */
    private boolean langJustInit = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        et = findViewById(R.id.et_address);
        et.setText(SettingsStore.getBaseUrl());
        btnTest = findViewById(R.id.btn_test);
        setupLanguageSpinner();

        findViewById(R.id.btn_test).setOnClickListener(v -> test());
        findViewById(R.id.btn_reset).setOnClickListener(v -> {
            SettingsStore.reset();
            et.setText(SettingsStore.getBaseUrl());
            Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_save).setOnClickListener(v -> save());
    }

    /**
     * 界面语言:中文 / English。
     *
     * 用 appcompat 的 setApplicationLocales(1.6.0 起内置),不自己写 attachBaseContext ——
     * 那样得给 9 个 Activity 都加基类。它自己会重建 Activity 让文案重新解析,
     * 也会持久化选择(API<33 靠 manifest 里那个 AppLocalesMetadataHolderService)。
     */
    private void setupLanguageSpinner() {
        Spinner sp = findViewById(R.id.sp_language);
        // 语言名按惯例各写各的语言,不随界面语言变 —— 所以直接取资源里的字面量
        String[] labels = {getString(R.string.settings_language_zh), getString(R.string.settings_language_en)};
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(LANG_EN.equals(currentLanguage()) ? 1 : 0);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String want = (position == 1) ? LANG_EN : LANG_ZH;
                if (langJustInit) { langJustInit = false; return; }   // 初始化那一次不算
                if (want.equals(currentLanguage())) return;           // 没变就别白重建一次
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(want));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    /** 当前生效的界面语言:应用里没设过(空)就跟随系统 */
    private String currentLanguage() {
        LocaleListCompat cur = AppCompatDelegate.getApplicationLocales();
        String tag = (cur == null || cur.isEmpty())
                ? Locale.getDefault().getLanguage()
                : cur.toLanguageTags();
        return (tag != null && tag.startsWith("en")) ? LANG_EN : LANG_ZH;
    }

    private void test() {
        String url = et.getText().toString().trim();
        if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            Toast.makeText(this, R.string.settings_test_fail, Toast.LENGTH_SHORT).show();
            return;
        }

        btnTest.setEnabled(false);
        btnTest.setText(R.string.settings_testing);

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
                            R.string.settings_test_success, Toast.LENGTH_SHORT).show();
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
                                getString(R.string.settings_test_fail_fmt, msg), Toast.LENGTH_SHORT).show();
                    } else if (msg != null && msg.contains(BackendMessages.NOT_LOGGED_IN)) {
                        // 响应体含「未登录」→ 这是 WMS 后端(只是没登录)。
                        // 这是靠后端文案做的**协议判断**,不是界面文案,不能翻译(见 BackendMessages)。
                        Toast.makeText(SettingsActivity.this,
                                R.string.settings_test_success, Toast.LENGTH_SHORT).show();
                    } else {
                        // 有 HTTP 响应但不是 WMS → 连到了其他服务
                        Toast.makeText(SettingsActivity.this,
                                R.string.settings_not_wms, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void save() {
        String url = et.getText().toString().trim();
        if (url.isEmpty()) {
            et.setError(getString(R.string.settings_address_required));
            return;
        }
        SettingsStore.setBaseUrl(url);
        Toast.makeText(this, R.string.msg_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
