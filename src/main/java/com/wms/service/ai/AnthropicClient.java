package com.wms.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Anthropic Messages API(/v1/messages)。
 *
 * 与 OpenAI 兼容端的差别只有三处:system 是顶层字段、鉴权头是 x-api-key + anthropic-version、
 * 流式事件是 content_block_delta。其余(body 组装、SSE 读法)尽量复用。
 */
public class AnthropicClient implements AiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VERSION = "2023-06-01";

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutMs;
    private final HttpClient http;

    public AnthropicClient(String baseUrl, String apiKey, String model, int timeoutMs) {
        String base = stripTrailingSlash(baseUrl);
        this.baseUrl = base.endsWith("/v1") ? base : base + "/v1";
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
            JsonNode root = MAPPER.readTree(res.body());
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) return block.path("text").asText("");
            }
            return "";
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
            // 同 OpenAI:流式不设 HttpRequest.timeout,避免整段响应被掐断
            HttpRequest req = requestBuilder()
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<java.io.InputStream> res =
                    http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("AI 服务返回 HTTP " + res.statusCode());
            }
            OpenAICompatibleClient.readSse(res.body(), (json, out) -> {
                if (!"content_block_delta".equals(json.path("type").asText())) return;
                String piece = json.path("delta").path("text").asText("");
                if (!piece.isEmpty()) out.accept(piece);
            }, onDelta);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AI 服务调用失败: " + e.getMessage(), e);
        }
    }

    private HttpRequest.Builder requestBuilder() {
        return HttpRequest.newBuilder(URI.create(baseUrl + "/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", VERSION);
    }

    private ObjectNode buildBody(String systemPrompt, List<ChatMessage> messages, int maxTokens, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("stream", stream);
        body.put("system", systemPrompt);
        ArrayNode arr = body.putArray("messages");
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
