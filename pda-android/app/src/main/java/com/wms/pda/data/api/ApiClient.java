package com.wms.pda.data.api;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.wms.pda.model.ApiResponse;
import com.wms.pda.data.repo.SettingsStore;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 真实后端 HTTP 客户端。OkHttp 3.12 + JWT 拦截器 + ApiResponse 解析。
 * 所有方法异步;回调统一切到主线程。
 *
 * 不抛 checked exception;失败转 ApiException(网络 -1 / HTTP -2 / 业务 code / 401)。
 */
public class ApiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final Gson gson = new Gson();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String baseUrl;
    private String token;

    public ApiClient() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(chain -> {
                    Request req = chain.request();
                    if (token != null && !token.isEmpty()) {
                        req = req.newBuilder().addHeader("Authorization", "Bearer " + token).build();
                    }
                    return chain.proceed(req);
                })
                .build();
        refreshBaseUrl();
    }

    /** 重新读取 SettingsStore 的 baseUrl(供"服务器设置"页保存后立即生效)。 */
    public void refreshBaseUrl() {
        String override = SettingsStore.getBaseUrl();
        this.baseUrl = trimTrailingSlash(
                (override == null || override.isEmpty())
                        ? com.wms.pda.BuildConfig.API_BASE_URL
                        : override);
    }

    public void setToken(String token) { this.token = token; }
    public String getBaseUrl() { return baseUrl; }

    private static String trimTrailingSlash(String url) {
        if (url == null) return url;
        String s = url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public <T> void get(String path, Class<T> dataType, Callback<T> cb) {
        execute(buildRequest(path, null, "GET"), dataType, cb);
    }

    public <T> void post(String path, Object body, Class<T> dataType, Callback<T> cb) {
        execute(buildRequest(path, body, "POST"), dataType, cb);
    }

    public <T> void put(String path, Object body, Class<T> dataType, Callback<T> cb) {
        execute(buildRequest(path, body, "PUT"), dataType, cb);
    }

    public <T> void delete(String path, Class<T> dataType, Callback<T> cb) {
        execute(buildRequest(path, null, "DELETE"), dataType, cb);
    }

    private Request buildRequest(String path, Object body, String method) {
        Request.Builder b = new Request.Builder().url(baseUrl + path);
        if (body != null) {
            String json = gson.toJson(body);
            b.method(method, RequestBody.create(JSON, json));
        } else if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            b.method(method, null);
        } else {
            b.method(method, RequestBody.create(JSON, "{}"));
        }
        return b.build();
    }

    private <T> void execute(Request req, Class<T> type, Callback<T> cb) {
        http.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, IOException e) {
                ui.post(() -> cb.onError(-1, "网络不可达: " + e.getMessage()));
            }
            @Override public void onResponse(okhttp3.Call call, Response resp) {
                ResponseBody rb = resp.body();
                final String bodyStr;
                try {
                    bodyStr = rb == null ? "" : rb.string();
                } catch (IOException ex) {
                    ui.post(() -> cb.onError(-3, "读取响应失败: " + ex.getMessage()));
                    if (rb != null) rb.close();
                    return;
                }
                if (rb != null) rb.close();
                if (!resp.isSuccessful()) {
                    final String msg = "HTTP " + resp.code() + ": " + bodyStr;
                    ui.post(() -> cb.onError(-2, msg));
                    return;
                }
                ApiResponse<T> ar;
                try {
                    ar = gson.fromJson(bodyStr, ApiResponse.class);
                } catch (JsonSyntaxException ex) {
                    ui.post(() -> cb.onError(-3, "JSON 解析失败: " + ex.getMessage()));
                    return;
                }
                if (ar == null) { ui.post(() -> cb.onError(-3, "空响应")); return; }
                if (ar.code != 0) {
                    final int c = ar.code;
                    final String m = ar.msg == null ? "" : ar.msg;
                    ui.post(() -> cb.onError(c, m));
                    return;
                }
                T data = null;
                if (type != Void.class && ar.data != null) {
                    JsonObject dataJson = gson.toJsonTree(ar.data).getAsJsonObject();
                    data = gson.fromJson(dataJson, type);
                }
                final T d = data;
                ui.post(() -> cb.onSuccess(d));
            }
        });
    }

    public interface Callback<T> {
        void onSuccess(T value);
        void onError(int code, String msg);
    }
}