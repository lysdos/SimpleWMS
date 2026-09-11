package com.wms.pda.data.repo;

import android.content.Context;
import android.content.SharedPreferences;

import com.wms.pda.BuildConfig;

/**
 * 服务器设置存储。用 SharedPreferences 保存 baseUrl。
 * 默认值取自 BuildConfig.API_BASE_URL(在 app/build.gradle 配置)。
 */
public final class SettingsStore {

    private static final String FILE = "wms_settings";
    private static final String KEY_BASE_URL = "base_url";

    private static SharedPreferences prefs;

    private SettingsStore() {}

    public static void init(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String getBaseUrl() {
        ensure();
        return prefs.getString(KEY_BASE_URL, BuildConfig.API_BASE_URL);
    }

    public static void setBaseUrl(String url) {
        ensure();
        prefs.edit().putString(KEY_BASE_URL, url).apply();
    }

    public static void reset() {
        ensure();
        prefs.edit().remove(KEY_BASE_URL).apply();
    }

    private static void ensure() {
        if (prefs == null) {
            throw new IllegalStateException("SettingsStore not initialized, call init() in Application");
        }
    }
}
