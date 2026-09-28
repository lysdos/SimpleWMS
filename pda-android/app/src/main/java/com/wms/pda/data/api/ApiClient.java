package com.wms.pda.data.api;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import com.wms.pda.model.ApiResponse;
import com.wms.pda.data.repo.SettingsStore;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
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
 * 不抛异常:所有失败都走 Callback.onError(网络 -1 / 非 2xx -2 / 解析 -3 / 后端业务码原样透传)。
 */
public class ApiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final Gson gson = new Gson();
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 在途请求。退出登录/页面销毁时用它把回调掐掉 —— 否则回调会打到已销毁的界面,
     *  或去取已被 reset() 清空的 RepositoryProvider(见 MainActivity 的退出登录)。 */
    private final Set<okhttp3.Call> inFlight = Collections.newSetFromMap(new ConcurrentHashMap<>());

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

    /** 取消全部在途请求(退出登录时调:别让回调落到已经离开的页面上) */
    public void cancelAll() {
        for (okhttp3.Call c : inFlight) {
            try { c.cancel(); } catch (Exception ignored) { }
        }
        inFlight.clear();
    }

    /**
     * 用**指定**地址构造探针客户端 —— 只给「设置」页的「测试连接」用。
     * 那里判定的是输入框里刚敲的地址,不能拿 SettingsStore 里那份旧值去测
     * (以前就是这样:测试永远验的是保存过的地址,新地址存进去才发现是错的)。
     */
    public static ApiClient forBaseUrl(String url) {
        ApiClient c = new ApiClient();
        c.baseUrl = trimTrailingSlash(url);
        return c;
    }

    /**
     * baseUrl 是不是一个能建连接地址。地址由用户在「设置」里手敲,少个 `http://` 就会让
     * `Request.Builder.url()` 抛 IllegalArgumentException —— 那是主线程上的硬崩,且地址已持久化,
     * 表现为"每次启动都崩"。这里提前判掉,回一句能看懂的错。
     */
    private boolean baseUrlInvalid() {
        if (baseUrl == null || baseUrl.isBlank()) return true;
        try {
            return HttpUrl.parse(baseUrl) == null;
        } catch (Exception e) {
            return true;
        }
    }

    private <T> boolean abortIfBaseUrlBad(Callback<T> cb) {
        if (!baseUrlInvalid()) return false;
        ui.post(() -> cb.onError(-1, "服务器地址无效,请到「设置」里修改"));
        return true;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) return url;
        String s = url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public <T> void get(String path, Class<T> dataType, Callback<T> cb) {
        get(path, (Type) dataType, cb);
    }

    /** data 为 JSON 数组时使用,如 TypeToken<List<Dto.Product>>(){}.getType() */
    public <T> void get(String path, Type dataType, Callback<T> cb) {
        if (abortIfBaseUrlBad(cb)) return;
        execute(buildRequest(path, null, "GET"), dataType, cb);
    }

    public <T> void post(String path, Object body, Class<T> dataType, Callback<T> cb) {
        if (abortIfBaseUrlBad(cb)) return;
        execute(buildRequest(path, body, "POST"), (Type) dataType, cb);
    }

    public <T> void put(String path, Object body, Class<T> dataType, Callback<T> cb) {
        if (abortIfBaseUrlBad(cb)) return;
        execute(buildRequest(path, body, "PUT"), (Type) dataType, cb);
    }

    public <T> void delete(String path, Class<T> dataType, Callback<T> cb) {
        if (abortIfBaseUrlBad(cb)) return;
        execute(buildRequest(path, null, "DELETE"), (Type) dataType, cb);
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

    private <T> void execute(Request req, Type type, Callback<T> cb) {
        okhttp3.Call call = http.newCall(req);
        inFlight.add(call);
        call.enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, IOException e) {
                inFlight.remove(call);
                // 被 cancelAll() 掐掉的请求不必再报"网络不可达"——用户已经登出/离开了
                if (call.isCanceled()) return;
                ui.post(() -> cb.onError(-1, "网络不可达: " + e.getMessage()));
            }
            @Override public void onResponse(okhttp3.Call call, Response resp) {
                inFlight.remove(call);
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
                    // 非 2xx 也优先取 ApiResponse.message。后端"资源不存在"现在是真 404,
                    // 若把整段 body 抛给用户会显示成 HTTP 404: {"code":404,...},既难读
                    // 又把接口实现细节泄到界面上。body 不是 JSON(如网关错误页)时退回原样。
                    String business = null;
                    try {
                        ApiResponse<?> er = gson.fromJson(bodyStr, ApiResponse.class);
                        if (er != null && er.message != null && !er.message.isEmpty()) {
                            business = er.message;
                        }
                    } catch (RuntimeException ignore) {
                        // 解析失败:用下面的兜底消息
                    }
                    final String msg = business != null ? business : "HTTP " + resp.code() + ": " + bodyStr;
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
                    final String m = ar.message == null ? "" : ar.message;
                    ui.post(() -> cb.onError(c, m));
                    return;
                }
                T data = null;
                if (ar.data != null) {
                    JsonElement el = gson.toJsonTree(ar.data);
                    if (!(type instanceof Class) || !Void.class.equals(type)) {
                        try {
                            data = gson.fromJson(el, type);
                        } catch (JsonSyntaxException ex) {
                            ui.post(() -> cb.onError(-3, "数据解析失败: " + ex.getMessage()));
                            return;
                        }
                    }
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