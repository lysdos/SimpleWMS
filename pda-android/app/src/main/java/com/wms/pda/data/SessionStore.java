package com.wms.pda.data;

import android.content.Context;
import android.content.SharedPreferences;

/** SharedPreferences 存 JWT + 用户名。 */
public class SessionStore {

    private static final String PREF = "wms_pda_session";
    private static final String K_TOKEN = "auth_token";
    private static final String K_USERNAME = "username";
    private static final String K_ROLE = "role";
    private static final String K_BASE_URL = "base_url";

    private final SharedPreferences sp;

    public SessionStore(Context ctx) {
        this.sp = ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String getToken() { return sp.getString(K_TOKEN, null); }
    public void setToken(String token) {
        sp.edit().putString(K_TOKEN, token).apply();
    }

    public String getUsername() { return sp.getString(K_USERNAME, null); }
    public void setUsername(String u) {
        sp.edit().putString(K_USERNAME, u).apply();
    }

    public String getRole() { return sp.getString(K_ROLE, null); }
    public void setRole(String r) {
        sp.edit().putString(K_ROLE, r).apply();
    }

    public String getBaseUrl() {
        return sp.getString(K_BASE_URL, null);
    }
    public void setBaseUrl(String url) {
        sp.edit().putString(K_BASE_URL, url).apply();
    }

    public void clear() {
        sp.edit().clear().apply();
    }
}
