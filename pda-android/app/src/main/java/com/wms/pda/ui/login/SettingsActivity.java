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

import okhttp3.HttpUrl;

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

    /**
     * 地址是否可用:http(s):// 前缀 + okhttp 能解析出 Host。
     *
     * 为什么必须校验:地址是要**持久化**的,而少一个 `http://` 会让
     * `Request.Builder.url()` 在主线程抛 IllegalArgumentException ——
     * 存进去一次,之后每次启动、每次请求都崩,只能靠进设置页改回来。
     */
    private static String normalizeUrl(String raw) {
        if (raw == null) return null;
        String url = raw.trim();
        if (url.isEmpty()) return null;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null;
        try {
            return HttpUrl.parse(url) == null ? null : url;
        } catch (Exception e) {
            return null;
        }
    }

    private void test() {
        String url = normalizeUrl(et.getText().toString());
        if (url == null) {
            Toast.makeText(this, R.string.settings_address_invalid, Toast.LENGTH_SHORT).show();
            return;
        }

        btnTest.setEnabled(false);
        btnTest.setText(R.string.settings_testing);

        // 用**输入框里**的地址建探针。以前是 new ApiClient() + refreshBaseUrl(),
        // 那读的是 SettingsStore 里的旧值 —— 输入新地址点「测试连接」测的却是老地址,
        // 显示"连接成功"之后把错的地址存进去。
        ApiClient probe = ApiClient.forBaseUrl(url);
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
        String raw = et.getText().toString().trim();
        if (raw.isEmpty()) {
            et.setError(getString(R.string.settings_address_required));
            return;
        }
        // save() 以前只判空:「192.168.1.5:9080」这种漏了 http:// 的地址会被照存,
        // 下次任何请求都会在主线程 url() 上抛异常直接崩(且地址已持久化,重启照样崩)。
        String url = normalizeUrl(raw);
        if (url == null) {
            et.setError(getString(R.string.settings_address_invalid));
            return;
        }
        SettingsStore.setBaseUrl(url);
        Toast.makeText(this, R.string.msg_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
