package com.wms.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * OpenAI 兼容端点(/chat/completions):Ollama、vLLM、DeepSeek 都用这套协议。
 *
 * 基址由配置决定,请求体里没有任何 URL 字段 —— 前端传不进来,杜绝 SSRF。
 */
public class OpenAICompatibleClient implements AiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutMs;
    private final HttpClient http;

    public OpenAICompatibleClient(String baseUrl, String apiKey, String model, int timeoutMs) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model;
        this.timeoutMs = timeoutMs;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    @Override
    public String complete(String systemPrompt, List<ChatMessage> messages, int maxTokens) {
        try {
            String body = buildBody(systemPrompt, messages, maxTokens, false).toString();
            HttpRequest req = requestBuilder()
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("AI 服务返回 HTTP " + res.statusCode());
            }
            return MAPPER.readTree(res.body())
                    .path("choices").path(0).path("message").path("content").asText("");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AI 服务调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void stream(String systemPrompt, List<ChatMessage> messages, int maxTokens, Consumer<String> onDelta) {
        try {
            String body = buildBody(systemPrompt, messages, maxTokens, true).toString();
            // 流式不设 HttpRequest.timeout:它覆盖整个响应体,会把长回答截断。
            // 超时只靠 HttpClient 的 connectTimeout,读超时由调用方(总时限)控制。
            HttpRequest req = requestBuilder()
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<java.io.InputStream> res =
                    http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("AI 服务返回 HTTP " + res.statusCode());
            }
            readSse(res.body(), (json, out) -> {
                String piece = json.path("choices").path(0).path("delta").path("content").asText("");
                if (!piece.isEmpty()) out.accept(piece);
            }, onDelta);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AI 服务调用失败: " + e.getMessage(), e);
        }
    }

    /** 逐行读 SSE,把 "data: {...}" 交给消费方。抽出来是为了和 Anthropic 客户端共用读法。 */
    static void readSse(java.io.InputStream in, SseConsumer consumer, Consumer<String> onDelta) throws Exception {
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
                consumer.accept(MAPPER.readTree(payload), onDelta);
            }
        }
    }

    @FunctionalInterface
    interface SseConsumer {
        void accept(com.fasterxml.jackson.databind.JsonNode json, Consumer<String> onDelta);
    }

    private HttpRequest.Builder requestBuilder() {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json");
        if (!apiKey.isBlank()) b.header("Authorization", "Bearer " + apiKey);
        return b;
    }

    private ObjectNode buildBody(String systemPrompt, List<ChatMessage> messages, int maxTokens, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("stream", stream);
        body.put("max_tokens", maxTokens);
        ArrayNode arr = body.putArray("messages");
        arr.addObject().put("role", "system").put("content", systemPrompt);
        for (ChatMessage m : messages) {
            arr.addObject().put("role", m.role()).put("content", m.content());
        }
        return body;
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
