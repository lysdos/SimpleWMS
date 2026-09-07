package com.wms.pda.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wms.pda.data.SessionStore;
import com.wms.pda.model.ApiResponse;

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
 * API 客户端 - OkHttp + JWT 拦截器 + 后端 ApiResponse 解析。
 * 失败抛 ApiException(网络 / HTTP / 业务 / 鉴权)。
 */
public class ApiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final SessionStore session;
    private final OkHttpClient http;
    private final Gson gson = new Gson();
    private final String baseUrl;

    public ApiClient(SessionStore session) {
        this.session = session;
        this.baseUrl = resolveBaseUrl(session);
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(authInterceptor())
                .build();
    }

    private static String resolveBaseUrl(SessionStore session) {
        String override = session.getBaseUrl();
        if (override != null && !override.isEmpty()) return override;
        return com.wms.pda.BuildConfig.API_BASE_URL;
    }

    private Interceptor authInterceptor() {
        return chain -> {
            Request req = chain.request();
            String token = session.getToken();
            if (token != null && !token.isEmpty()) {
                req = req.newBuilder()
                        .addHeader("Authorization", "Bearer " + token)
                        .build();
            }
            return chain.proceed(req);
        };
    }

    public <T> T get(String path, Class<T> type) throws ApiException {
        return execute(buildRequest(path, null, "GET"), type);
    }

    public <T> T post(String path, Object body, Class<T> type) throws ApiException {
        return execute(buildRequest(path, body, "POST"), type);
    }

    public <T> T put(String path, Object body, Class<T> type) throws ApiException {
        return execute(buildRequest(path, body, "PUT"), type);
    }

    private Request buildRequest(String path, Object body, String method) {
        Request.Builder b = new Request.Builder().url(baseUrl + path);
        if (body != null) {
            String json = gson.toJson(body);
            b.method(method, RequestBody.create(JSON, json));
        } else {
            b.method(method, body == null && "GET".equals(method) ? null : null);
        }
        return b.build();
    }

    private <T> T execute(Request req, Class<T> type) throws ApiException {
        try (Response resp = http.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            String bodyStr = rb == null ? "" : rb.string();
            if (!resp.isSuccessful()) {
                throw new ApiException(ApiException.ERR_HTTP,
                        "HTTP " + resp.code() + ": " + bodyStr);
            }
            ApiResponse<?> ar;
            try {
                ar = gson.fromJson(bodyStr, ApiResponse.class);
            } catch (Exception e) {
                throw new ApiException(ApiException.ERR_PARSE, "JSON 解析失败: " + e.getMessage());
            }
            if (ar == null) throw new ApiException(ApiException.ERR_PARSE, "空响应");
            if (ar.code == 401) {
                session.clear();
                throw new ApiException(ApiException.ERR_AUTH, ar.msg);
            }
            if (ar.code != 0) {
                throw new ApiException(ar.code, ar.msg);
            }
            if (type == Void.class || ar.data == null) return null;
            JsonObject dataJson = gson.toJsonTree(ar.data).getAsJsonObject();
            return gson.fromJson(dataJson, type);
        } catch (IOException e) {
            throw new ApiException(ApiException.ERR_NETWORK, "网络不可达: " + e.getMessage());
        }
    }

    public String getBaseUrl() { return baseUrl; }
}
