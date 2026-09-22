package com.wms.pda.data.repo;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 登录态存储:用户名、JWT token、角色。
 * 登录态存储:用户名、JWT token、角色。
 */
public final class SessionStore {

    private static final String FILE = "wms_session";
    private static final String KEY_USER = "username";
    private static final String KEY_ROLE = "role";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_LOGGED = "logged_in";

    private static SharedPreferences prefs;

    private SessionStore() {}

    public static void init(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isLoggedIn() {
        ensure();
        return prefs.getBoolean(KEY_LOGGED, false);
    }

    public static void login(String username, String token, String role) {
        ensure();
        prefs.edit()
                .putString(KEY_USER, username)
                .putString(KEY_TOKEN, token == null ? "" : token)
                .putString(KEY_ROLE, role == null ? "" : role)
                .putBoolean(KEY_LOGGED, true)
                .apply();
    }

    public static void logout() {
        ensure();
        prefs.edit()
                .remove(KEY_USER)
                .remove(KEY_TOKEN)
                .remove(KEY_ROLE)
                .putBoolean(KEY_LOGGED, false)
                .apply();
    }

    /** 当前登录账号。异常态(有登录标记但没存下账号)返回 null,由调用方给占位符 */
    public static String currentUser() {
        ensure();
        return prefs.getString(KEY_USER, null);
    }

    public static String token() {
        ensure();
        return prefs.getString(KEY_TOKEN, null);
    }

    public static String role() {
        ensure();
        return prefs.getString(KEY_ROLE, "OPERATOR");
    }

    private static void ensure() {
        if (prefs == null) {
            throw new IllegalStateException("SessionStore not initialized, call init() in Application");
        }
    }
}