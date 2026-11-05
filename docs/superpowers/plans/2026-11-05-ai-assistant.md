# Web 端 AI 助手 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Web 界面右下角加一个只读 AI 助手,用户用自然语言问库存/效期/单据,助手按当前用户权限取数并给出中文回答与业务建议。

**Architecture:** 两轮式取数——模型先输出受约束的 JSON 查询计划,后端白名单校验后执行只读工具,再把数据交给模型流式生成答案。Provider 可切换(OpenAI 兼容 / Anthropic),两家都用 JDK 自带 `HttpClient` + Jackson 手写,不加新依赖。

**Tech Stack:** Spring Boot 3.3(Spring MVC + Spring JDBC)、Jackson、JUnit 5 + Mockito、原生 JS 前端。

## Global Constraints

- **不引入任何新 Maven 依赖**。HTTP 用 `java.net.http.HttpClient`,JSON 用已有的 Jackson。离线仓库 `.m2-repo` 不需要更新。
- **只读**:AI 工具不得调用任何写入方法(建单/提交/调拨/报废/改库存)。
- **金额一律 `BigDecimal`**,禁止 `double`/`float`;序列化保留两位。
- **日志不得包含问题原文、答案内容、工具返回数据**(含商品名、供应商、价格)。只记 userId / 工具名 / 耗时 / token 数 / degraded 标记 / 错误摘要。
- **分层**:Controller 只收参校验,业务在 Service,数据访问在 Dao。AI 取数不得直连 DAO,必须经 Service。
- **无表结构变更**,不需要 SQL 脚本。
- **每个任务结束必须能编译通过**。
- 构建/测试命令(本机 `mvn` 不在 PATH):
  - 编译:`C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo compile`
  - 单测:`C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=<类名> test`
  - 全量:`C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo test`
- 分支:`design/ai-assistant`,每个任务一次 commit。

---

### Task 1: 配置类与调用契约

**Files:**
- Create: `src/main/java/com/wms/service/ai/AiSettings.java`
- Create: `src/main/java/com/wms/service/ai/ChatMessage.java`
- Create: `src/main/java/com/wms/service/ai/AiClient.java`
- Test: `src/test/java/com/wms/service/ai/AiSettingsTest.java`

**Interfaces:**
- Produces:
  - `AiSettings.isEnabled(): boolean`、`AiSettings.isAnthropic(): boolean`
  - `AiSettings.getProvider()/getBaseUrl()/getApiKey()/getModel(): String`
  - `AiSettings.getTimeoutMs()/getMaxToolsPerQuestion()/getMaxQuestionLength()/getRateLimitPerMinute(): int`
  - `ChatMessage(String role, String content)`,静态工厂 `ChatMessage.user(String)` / `ChatMessage.assistant(String)`
  - `AiClient.complete(String systemPrompt, List<ChatMessage> messages, int maxTokens): String`
  - `AiClient.stream(String systemPrompt, List<ChatMessage> messages, int maxTokens, Consumer<String> onDelta): void`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/wms/service/ai/AiSettingsTest.java`:

```java
package com.wms.service.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSettingsTest {

    private static AiSettings settings(boolean enabled, String provider) {
        return new AiSettings(enabled, provider, "http://localhost:11434/v1", "",
                "qwen2.5:7b", 30000, 3, 500, 6);
    }

    @Test
    void disabled_by_default_keeps_feature_off() {
        assertFalse(settings(false, "openai-compatible").isEnabled());
    }

    @Test
    void anthropic_provider_is_recognised_case_insensitively() {
        assertTrue(settings(true, "Anthropic").isAnthropic());
        assertFalse(settings(true, "openai-compatible").isAnthropic());
    }

    @Test
    void getters_expose_configured_values() {
        AiSettings s = settings(true, "openai-compatible");
        assertEquals("qwen2.5:7b", s.getModel());
        assertEquals(30000, s.getTimeoutMs());
        assertEquals(3, s.getMaxToolsPerQuestion());
        assertEquals(500, s.getMaxQuestionLength());
        assertEquals(6, s.getRateLimitPerMinute());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=AiSettingsTest test
```

Expected: 编译失败,`AiSettings` 找不到符号。

- [ ] **Step 3: 写实现**

`AiSettings.java`(构造器 `@Value` 注入,与项目现有 `JwtService` / `UserService` 风格一致):

```java
package com.wms.service.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AI 助手的全部配置。默认关闭:没配模型地址时前端不该出现入口。 */
@Component
public class AiSettings {

    private final boolean enabled;
    private final String provider;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutMs;
    private final int maxToolsPerQuestion;
    private final int maxQuestionLength;
    private final int rateLimitPerMinute;

    public AiSettings(
            @Value("${wms.ai.enabled:false}") boolean enabled,
            @Value("${wms.ai.provider:openai-compatible}") String provider,
            @Value("${wms.ai.base-url:http://localhost:11434/v1}") String baseUrl,
            @Value("${wms.ai.api-key:}") String apiKey,
            @Value("${wms.ai.model:qwen2.5:7b}") String model,
            @Value("${wms.ai.timeout-ms:30000}") int timeoutMs,
            @Value("${wms.ai.max-tools-per-question:3}") int maxToolsPerQuestion,
            @Value("${wms.ai.max-question-length:500}") int maxQuestionLength,
            @Value("${wms.ai.rate-limit-per-minute:6}") int rateLimitPerMinute) {
        this.enabled = enabled;
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model;
        this.timeoutMs = timeoutMs;
        this.maxToolsPerQuestion = maxToolsPerQuestion;
        this.maxQuestionLength = maxQuestionLength;
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public boolean isEnabled() { return enabled; }
    public boolean isAnthropic() { return "anthropic".equalsIgnoreCase(provider); }
    public String getProvider() { return provider; }
    public String getBaseUrl() { return baseUrl; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public int getTimeoutMs() { return timeoutMs; }
    public int getMaxToolsPerQuestion() { return maxToolsPerQuestion; }
    public int getMaxQuestionLength() { return maxQuestionLength; }
    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
}
```

`ChatMessage.java`:

```java
package com.wms.service.ai;

/** 一条对话消息。role 只用 "user" / "assistant"(system 走独立参数,不混进列表)。 */
public record ChatMessage(String role, String content) {

    public static ChatMessage user(String content) { return new ChatMessage("user", content); }
    public static ChatMessage assistant(String content) { return new ChatMessage("assistant", content); }
}
```

`AiClient.java`:

```java
package com.wms.service.ai;

import java.util.List;
import java.util.function.Consumer;

/** 模型调用的最小接口。两个 provider 各自实现,上层不关心差异。 */
public interface AiClient {

    /** 非流式:用于第一轮,只让它输出 JSON 查询计划。 */
    String complete(String systemPrompt, List<ChatMessage> messages, int maxTokens);

    /** 流式:用于第二轮答案,每收到一段文本回调一次 onDelta。 */
    void stream(String systemPrompt, List<ChatMessage> messages, int maxTokens, Consumer<String> onDelta);
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=AiSettingsTest test
```

Expected: PASS(3 个用例)。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/wms/service/ai/AiSettings.java \
        src/main/java/com/wms/service/ai/ChatMessage.java \
        src/main/java/com/wms/service/ai/AiClient.java \
        src/test/java/com/wms/service/ai/AiSettingsTest.java
git commit -m "feat(ai): 配置类与模型调用契约"
```

---

### Task 2: OpenAI 兼容客户端(非流式)

**Files:**
- Create: `src/main/java/com/wms/service/ai/OpenAICompatibleClient.java`
- Test: `src/test/java/com/wms/service/ai/OpenAICompatibleClientTest.java`

**Interfaces:**
- Consumes: `AiClient`、`ChatMessage`(Task 1)
- Produces: `OpenAICompatibleClient(String baseUrl, String apiKey, String model, int timeoutMs)`,实现 `AiClient`

- [ ] **Step 1: 写失败的测试**

用 JDK 自带的 `com.sun.net.httpserver.HttpServer` 起一个只监听回环的假服务,**测试不出网**。

```java
package com.wms.service.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAICompatibleClientTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void complete_posts_json_and_returns_message_content() {
        server.createContext("/v1/chat/completions", ex -> {
            assertEquals("Bearer secret", ex.getRequestHeaders().getFirst("Authorization"));
            String reply = "{\"choices\":[{\"message\":{\"content\":\"共 128 件\"}}]}";
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });

        OpenAICompatibleClient client = new OpenAICompatibleClient(baseUrl, "secret", "test-model", 5000);
        String out = client.complete("你是仓库助手", List.of(ChatMessage.user("洗发水还有多少")), 64);

        assertEquals("共 128 件", out);
    }

    @Test
    void non_2xx_throws_with_status_code() {
        server.createContext("/v1/chat/completions", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });

        OpenAICompatibleClient client = new OpenAICompatibleClient(baseUrl, "", "test-model", 5000);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.complete("sys", List.of(ChatMessage.user("hi")), 64));
        assertEquals("AI 服务返回 HTTP 500", e.getMessage());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=OpenAICompatibleClientTest test
```

Expected: 编译失败,`OpenAICompatibleClient` 找不到符号。

- [ ] **Step 3: 写实现**

```java
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
```

- [ ] **Step 4: 跑测试确认通过**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=OpenAICompatibleClientTest test
```

Expected: PASS(2 个用例)。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/wms/service/ai/OpenAICompatibleClient.java src/test/java/com/wms/service/ai/OpenAICompatibleClientTest.java
git commit -m "feat(ai): OpenAI 兼容客户端(非流式)"
```

---

### Task 3: Provider 装配与流式验证

**Files:**
- Create: `src/main/java/com/wms/config/AiClientConfig.java`
- Test: `src/test/java/com/wms/config/AiClientConfigTest.java`
- Modify: `src/test/java/com/wms/service/ai/OpenAICompatibleClientTest.java`(追加流式用例)

**Interfaces:**
- Consumes: `AiSettings`、`OpenAICompatibleClient`(Task 1/2)
- Produces: Spring Bean `AiClient`,按 `wms.ai.provider` 选实现

- [ ] **Step 1: 写失败的测试**

`src/test/java/com/wms/config/AiClientConfigTest.java`:

```java
package com.wms.config;

import com.wms.service.ai.AiClient;
import com.wms.service.ai.AiSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiClientConfigTest {

    private static AiSettings settings(String provider) {
        return new AiSettings(true, provider, "http://localhost:11434/v1", "",
                "qwen2.5:7b", 30000, 3, 500, 6);
    }

    @Test
    void openai_compatible_provider_builds_openai_client() {
        AiClient client = new AiClientConfig().aiClient(settings("openai-compatible"));
        assertInstanceOf(com.wms.service.ai.OpenAICompatibleClient.class, client);
    }

    @Test
    void unknown_provider_fails_at_wiring_time() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AiClientConfig().aiClient(settings("some-vendor")));
        assertTrue(e.getMessage().contains("provider"));
    }
}
```

> `anthropic` 分支的装配与用例放在 Task 4 —— 那个任务才会引入 `AnthropicClient`。
> 这样每个任务结束时测试都是绿的,不留"等下一个任务才转红"的中间态。

- [ ] **Step 2: 跑测试确认失败**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=AiClientConfigTest test
```

Expected: 编译失败,`AiClientConfig` 找不到符号。

- [ ] **Step 3: 写实现**

`src/main/java/com/wms/config/AiClientConfig.java`:

```java
package com.wms.config;

import com.wms.service.ai.AiClient;
import com.wms.service.ai.AiSettings;
import com.wms.service.ai.OpenAICompatibleClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按配置装配 AiClient。换 provider 只改 application.yml 三行,业务代码不动。
 *
 * 未知 provider 在**启动阶段**就失败,而不是等用户点开助手才报错 —— 配错了就该立刻知道。
 */
@Configuration
public class AiClientConfig {

    @Bean
    public AiClient aiClient(AiSettings settings) {
        if (settings.isAnthropic()) {
            throw new IllegalStateException("provider=anthropic 尚未装配(Task 4 接入 AnthropicClient)");
        }
        if (!"openai-compatible".equalsIgnoreCase(settings.getProvider())) {
            throw new IllegalStateException("不支持的 wms.ai.provider: " + settings.getProvider()
                    + "(可选 openai-compatible / anthropic)");
        }
        return new OpenAICompatibleClient(settings.getBaseUrl(), settings.getApiKey(),
                settings.getModel(), settings.getTimeoutMs());
    }
}
```

同一任务里补上 OpenAI 流式的验证用例(实现已在 Task 2,这里钉住解析行为)。在
`OpenAICompatibleClientTest` 追加:

```java
    @Test
    void stream_emits_only_non_empty_deltas_and_stops_at_done() {
        server.createContext("/v1/chat/completions", ex -> {
            String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"共 128 \"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"件\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{}}]}\n\n"
                    + "data: [DONE]\n\n";
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });

        OpenAICompatibleClient client = new OpenAICompatibleClient(baseUrl, "", "test-model", 5000);
        StringBuilder acc = new StringBuilder();
        client.stream("sys", List.of(ChatMessage.user("hi")), 64, acc::append);

        assertEquals("共 128 件", acc.toString());
    }
```

- [ ] **Step 4: 跑测试确认通过**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest='AiClientConfigTest,OpenAICompatibleClientTest' test
```

Expected: PASS(2 个装配用例 + 3 个 OpenAI 用例全绿)。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/wms/config/AiClientConfig.java \
        src/test/java/com/wms/config/AiClientConfigTest.java \
        src/test/java/com/wms/service/ai/OpenAICompatibleClientTest.java
git commit -m "feat(ai): provider 装配与流式解析验证"
```

---

### Task 4: Anthropic 客户端与装配

**Files:**
- Create: `src/main/java/com/wms/service/ai/AnthropicClient.java`
- Modify: `src/main/java/com/wms/config/AiClientConfig.java`(接入 anthropic 分支)
- Test: `src/test/java/com/wms/service/ai/AnthropicClientTest.java`
- Modify: `src/test/java/com/wms/config/AiClientConfigTest.java`(追加 anthropic 装配用例)

**Interfaces:**
- Consumes: `AiClient`、`ChatMessage`、`OpenAICompatibleClient.readSse(...)`(Task 1/2)
- Produces: `AnthropicClient(String baseUrl, String apiKey, String model, int timeoutMs)`

- [ ] **Step 1: 写失败的测试**

```java
package com.wms.service.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicClientTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();   // 不带 /v1
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void complete_sends_anthropic_headers_and_reads_text_block() {
        server.createContext("/v1/messages", ex -> {
            assertEquals("secret-key", ex.getRequestHeaders().getFirst("x-api-key"));
            assertEquals("2023-06-01", ex.getRequestHeaders().getFirst("anthropic-version"));
            String reply = "{\"content\":[{\"type\":\"text\",\"text\":\"共 128 件\"}]}";
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });

        AnthropicClient client = new AnthropicClient(baseUrl, "secret-key", "claude-opus-4-8", 5000);
        String out = client.complete("sys", List.of(ChatMessage.user("q")), 64);

        assertEquals("共 128 件", out);
    }

    @Test
    void stream_emits_text_from_content_block_delta() {
        server.createContext("/v1/messages", ex -> {
            String sse = "event: content_block_delta\n"
                    + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"共 128 \"}}\n\n"
                    + "event: content_block_delta\n"
                    + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"件\"}}\n\n"
                    + "event: message_stop\n"
                    + "data: {\"type\":\"message_stop\"}\n\n";
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });

        AnthropicClient client = new AnthropicClient(baseUrl, "k", "claude-opus-4-8", 5000);
        StringBuilder acc = new StringBuilder();
        client.stream("sys", List.of(ChatMessage.user("q")), 64, acc::append);

        assertEquals("共 128 件", acc.toString());
    }

    @Test
    void base_url_already_ending_with_v1_is_not_doubled() {
        server.createContext("/v1/messages", ex -> {
            String reply = "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}";
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });

        AnthropicClient client = new AnthropicClient(baseUrl + "/v1", "k", "claude-opus-4-8", 5000);
        assertEquals("ok", client.complete("sys", List.of(ChatMessage.user("q")), 64));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=AnthropicClientTest test
```

Expected: 编译失败(Task 3 里只建了骨架)或 `AssertionError`。

- [ ] **Step 3: 写实现**

```java
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
```

- [ ] **Step 4: 接入 anthropic 装配**

接着把 anthropic 分支接进 `AiClientConfig`(替换 Task 3 里那行占位异常):

```java
    @Bean
    public AiClient aiClient(AiSettings settings) {
        if (settings.isAnthropic()) {
            if (settings.getApiKey().isBlank()) {
                throw new IllegalStateException(
                        "provider=anthropic 时必须配置 wms.ai.api-key(环境变量 WMS_AI_API_KEY)");
            }
            return new AnthropicClient(settings.getBaseUrl(), settings.getApiKey(),
                    settings.getModel(), settings.getTimeoutMs());
        }
        if (!"openai-compatible".equalsIgnoreCase(settings.getProvider())) {
            throw new IllegalStateException("不支持的 wms.ai.provider: " + settings.getProvider()
                    + "(可选 openai-compatible / anthropic)");
        }
        return new OpenAICompatibleClient(settings.getBaseUrl(), settings.getApiKey(),
                settings.getModel(), settings.getTimeoutMs());
    }
```

并在 `AiClientConfigTest` 追加两个用例:

```java
    private static AiSettings anthropicSettings(String apiKey) {
        return new AiSettings(true, "anthropic", "https://api.anthropic.com", apiKey,
                "claude-opus-4-8", 30000, 3, 500, 6);
    }

    @Test
    void anthropic_provider_builds_anthropic_client() {
        AiClient client = new AiClientConfig().aiClient(anthropicSettings("sk-test"));
        assertInstanceOf(com.wms.service.ai.AnthropicClient.class, client);
    }

    @Test
    void anthropic_without_key_is_rejected_at_wiring_time() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AiClientConfig().aiClient(anthropicSettings("")));
        assertTrue(e.getMessage().contains("api-key"));
    }
```

- [ ] **Step 5: 跑测试确认通过**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest='AnthropicClientTest,AiClientConfigTest' test
```

Expected: PASS(3 个 Anthropic 用例 + 4 个装配用例)。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/wms/service/ai/AnthropicClient.java \
        src/main/java/com/wms/config/AiClientConfig.java \
        src/test/java/com/wms/service/ai/AnthropicClientTest.java \
        src/test/java/com/wms/config/AiClientConfigTest.java
git commit -m "feat(ai): Anthropic 客户端与 provider 装配"
```

---

### Task 5: 每用户限流

**Files:**
- Create: `src/main/java/com/wms/service/ai/AiThrottle.java`
- Test: `src/test/java/com/wms/service/ai/AiThrottleTest.java`

**Interfaces:**
- Produces: `AiThrottle.check(int userId): void`,超出抛 `TooManyRequestsException`(`GlobalExceptionHandler` 已映射成 429)

- [ ] **Step 1: 写失败的测试**

```java
package com.wms.service.ai;

import com.wms.controller.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiThrottleTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AiThrottle throttle = new AiThrottle(6, now::get);

    @Test
    void allows_up_to_limit_within_a_minute() {
        for (int i = 0; i < 6; i++) assertDoesNotThrow(() -> throttle.check(1));
    }

    @Test
    void seventh_call_within_a_minute_is_rejected() {
        for (int i = 0; i < 6; i++) throttle.check(1);
        assertThrows(TooManyRequestsException.class, () -> throttle.check(1));
    }

    @Test
    void window_slides_after_one_minute() {
        for (int i = 0; i < 6; i++) throttle.check(1);
        assertThrows(TooManyRequestsException.class, () -> throttle.check(1));
        now.addAndGet(61_000L);
        assertDoesNotThrow(() -> throttle.check(1));
    }

    @Test
    void counters_are_per_user() {
        for (int i = 0; i < 6; i++) throttle.check(1);
        assertDoesNotThrow(() -> throttle.check(2));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=AiThrottleTest test
```

Expected: 编译失败,`AiThrottle` 找不到符号。

- [ ] **Step 3: 写实现**

```java
package com.wms.service.ai;

import com.wms.controller.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.LongSupplier;

/**
 * AI 请求的每用户限流(进程内滑动窗口,单实例够用,与 LoginThrottle 同思路)。
 *
 * LLM 调用慢且贵,没有这道闸门时连点几下就能把模型服务打满。
 */
@Component
public class AiThrottle {

    private static final long WINDOW_MS = 60_000L;

    private final int limitPerMinute;
    private final LongSupplier clock;
    private final Map<Integer, Deque<Long>> hits = new ConcurrentHashMap<>();

    @Autowired
    public AiThrottle(@Value("${wms.ai.rate-limit-per-minute:6}") int limitPerMinute) {
        this(limitPerMinute, System::currentTimeMillis);
    }

    /** 测试用:注入可控时钟 */
    AiThrottle(int limitPerMinute, LongSupplier clock) {
        this.limitPerMinute = limitPerMinute;
        this.clock = clock;
    }

    public void check(int userId) {
        long now = clock.getAsLong();
        Deque<Long> q = hits.computeIfAbsent(userId, k -> new ConcurrentLinkedDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) q.pollFirst();
            if (q.size() >= limitPerMinute) {
                throw new TooManyRequestsException("AI 助手请求过于频繁,请稍后再试");
            }
            q.addLast(now);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=AiThrottleTest test
```

Expected: PASS(4 个用例)。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/wms/service/ai/AiThrottle.java src/test/java/com/wms/service/ai/AiThrottleTest.java
git commit -m "feat(ai): 每用户限流"
```

---

### Task 6: 供货价查询(SupplierService 只读方法)

**Files:**
- Modify: `src/main/java/com/wms/service/SupplierService.java`(追加方法)
- Test: `src/test/java/com/wms/service/SupplierServiceQuotesTest.java`

**Interfaces:**
- Consumes: `SupplierDao.findSuppliersByProduct(int): List<ProductSupplier>`(已存在)
- Produces: `SupplierService.listQuotesByProduct(int productId): List<ProductSupplier>`

- [ ] **Step 1: 写失败的测试**

```java
package com.wms.service;

import com.wms.dao.SupplierDao;
import com.wms.model.ProductSupplier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SupplierServiceQuotesTest {

    private final SupplierDao supplierDao = mock(SupplierDao.class);
    private final SupplierService service = new SupplierService(supplierDao);

    @Test
    void returns_quotes_with_bigdecimal_prices() {
        ProductSupplier ps = new ProductSupplier();
        ps.setSupplierName("华兴");
        ps.setUnitPrice(new BigDecimal("12.50"));
        when(supplierDao.findSuppliersByProduct(7)).thenReturn(List.of(ps));

        List<ProductSupplier> out = service.listQuotesByProduct(7);

        assertEquals(1, out.size());
        assertEquals(new BigDecimal("12.50"), out.get(0).getUnitPrice());
    }

    @Test
    void rejects_non_positive_product_id_without_touching_dao() {
        assertThrows(IllegalArgumentException.class, () -> service.listQuotesByProduct(0));
        verifyNoInteractions(supplierDao);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=SupplierServiceQuotesTest test
```

Expected: 编译失败,`listQuotesByProduct` 找不到符号。

- [ ] **Step 3: 写实现**

在 `SupplierService.java` 的 `list(String keyword, String status)` 之后追加:

```java
    /** 某商品的全部供货报价(供应商名 + 供货价)。AI 助手取数用,只读。 */
    @Transactional(readOnly = true)
    public List<com.wms.model.ProductSupplier> listQuotesByProduct(int productId) {
        if (productId <= 0) throw new IllegalArgumentException("商品 id 不合法: " + productId);
        return supplierDao.findSuppliersByProduct(productId);
    }
```

并在文件顶部(与其它 import 一起)补 `java.util.List` 与 `org.springframework.transaction.annotation.Transactional`
(若已存在则跳过)。

- [ ] **Step 4: 跑测试确认通过**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=SupplierServiceQuotesTest test
```

Expected: PASS(2 个用例)。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/wms/service/SupplierService.java src/test/java/com/wms/service/SupplierServiceQuotesTest.java
git commit -m "feat(ai): SupplierService 增加只读的供货价查询"
```

---

### Task 7: 只读工具集

**Files:**
- Create: `src/main/java/com/wms/service/ai/ToolCall.java`
- Create: `src/main/java/com/wms/service/ai/AiTools.java`
- Test: `src/test/java/com/wms/service/ai/AiToolsTest.java`

**Interfaces:**
- Consumes: `ProductService.list(String)`、`InventoryItemService.list(...)/count(...)`、`DashboardService.expiringItems(String,int)`、`InboundService.listOrders(...)`、`OutboundService.listOrders(...)`、`SupplierService.listQuotesByProduct(int)`(Task 6)
- Produces:
  - `ToolCall(String name, Map<String,Object> args)`
  - `AiTools.SUPPORTED: Set<String>`(6 个工具名)
  - `AiTools.execute(ToolCall call, String role): Map<String,Object>`,返回 `{tool, result}` 或 `{tool, error}`

- [ ] **Step 1: 写失败的测试**

```java
package com.wms.service.ai;

import com.wms.model.Product;
import com.wms.model.ProductSupplier;
import com.wms.service.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiToolsTest {

    private final ProductService productService = mock(ProductService.class);
    private final InventoryItemService itemService = mock(InventoryItemService.class);
    private final DashboardService dashboardService = mock(DashboardService.class);
    private final InboundService inboundService = mock(InboundService.class);
    private final OutboundService outboundService = mock(OutboundService.class);
    private final SupplierService supplierService = mock(SupplierService.class);

    private final AiTools tools = new AiTools(productService, itemService, dashboardService,
            inboundService, outboundService, supplierService);

    private static Product product(String sku, String name, int qty, int lowStock) {
        Product p = new Product();
        p.setSku(sku);
        p.setName(name);
        p.setQuantity(qty);
        p.setLowStock(lowStock);
        return p;
    }

    @Test
    void operator_cannot_read_supplier_quotes() {
        Map<String, Object> out = tools.execute(
                new ToolCall("supplier_quotes", Map.of("productId", 7)), "OPERATOR");

        assertNotNull(out.get("error"));
        assertTrue(((String) out.get("error")).contains("无权限"));
        verify(supplierService, never()).listQuotesByProduct(anyInt());
    }

    @Test
    void admin_gets_supplier_quotes_with_two_decimal_prices() {
        ProductSupplier ps = new ProductSupplier();
        ps.setSupplierName("华兴");
        ps.setUnitPrice(new BigDecimal("12.5"));
        when(supplierService.listQuotesByProduct(7)).thenReturn(List.of(ps));

        Map<String, Object> out = tools.execute(
                new ToolCall("supplier_quotes", Map.of("productId", 7)), "ADMIN");

        assertEquals("12.50", ((Map<?, ?>) ((List<?>) out.get("result")).get(0)).get("unitPrice"));
    }

    @Test
    void find_product_truncates_to_the_requested_limit() {
        List<Product> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(product("S" + i, "商品" + i, 5, 10));
        when(productService.list("商品")).thenReturn(many);

        Map<String, Object> out = tools.execute(
                new ToolCall("find_product", Map.of("keyword", "商品", "limit", 3)), "OPERATOR");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) out.get("result");
        assertEquals(12, result.get("total"));
        assertEquals(3, ((List<?>) result.get("items")).size());
    }

    @Test
    void find_product_without_keyword_returns_error_instead_of_throwing() {
        Map<String, Object> out = tools.execute(new ToolCall("find_product", Map.of()), "OPERATOR");
        assertNotNull(out.get("error"));
    }

    @Test
    void low_stock_filters_by_each_products_own_threshold() {
        when(productService.list(null)).thenReturn(List.of(
                product("A", "缺货", 3, 10),
                product("B", "充足", 50, 10)));
        when(productService.list(anyString())).thenThrow(new AssertionError("不该按关键字查"));

        Map<String, Object> out = tools.execute(new ToolCall("low_stock", Map.of()), "OPERATOR");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("result");
        assertEquals(1, rows.size());
        assertEquals("A", rows.get(0).get("sku"));
    }

    @Test
    void unknown_tool_is_reported_as_error() {
        Map<String, Object> out = tools.execute(new ToolCall("drop_database", Map.of()), "ADMIN");
        assertNotNull(out.get("error"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=AiToolsTest test
```

Expected: 编译失败,`ToolCall` / `AiTools` 找不到符号。

- [ ] **Step 3: 写实现**

`ToolCall.java`:

```java
package com.wms.service.ai;

import java.util.Map;

/** 模型给出的一个工具调用意图。args 是未经任何信任校验的原始参数。 */
public record ToolCall(String name, Map<String, Object> args) {
}
```

`AiTools.java`:

```java
package com.wms.service.ai;

import com.wms.model.InboundOrder;
import com.wms.model.InventoryItem;
import com.wms.model.OutboundOrder;
import com.wms.model.Product;
import com.wms.model.ProductSupplier;
import com.wms.service.DashboardService;
import com.wms.service.InboundService;
import com.wms.service.InventoryItemService;
import com.wms.service.OutboundService;
import com.wms.service.ProductService;
import com.wms.service.SupplierService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具白名单与执行。**全部只读**,没有也不会新增任何写入方法。
 *
 * 模型输出一律按不可信输入处理:工具名必须在白名单内,参数逐项校验并夹紧范围。
 * 单个工具失败只回填 error,不影响整轮问答 —— 用户仍能从其它工具的结果里得到答案。
 */
@Component
public class AiTools {

    public static final Set<String> SUPPORTED = Set.of(
            "find_product", "stock_by_sku", "low_stock", "expiring", "recent_orders", "supplier_quotes");

    /** 只有 ADMIN 能看的工具 */
    private static final Set<String> ADMIN_ONLY = Set.of("supplier_quotes");

    private static final int MAX_ROWS = 20;

    private final ProductService productService;
    private final InventoryItemService itemService;
    private final DashboardService dashboardService;
    private final InboundService inboundService;
    private final OutboundService outboundService;
    private final SupplierService supplierService;

    @Autowired
    public AiTools(ProductService productService, InventoryItemService itemService,
                   DashboardService dashboardService, InboundService inboundService,
                   OutboundService outboundService, SupplierService supplierService) {
        this.productService = productService;
        this.itemService = itemService;
        this.dashboardService = dashboardService;
        this.inboundService = inboundService;
        this.outboundService = outboundService;
        this.supplierService = supplierService;
    }

    public Map<String, Object> execute(ToolCall call, String role) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", call.name());
        try {
            out.put("result", dispatch(call, role));
        } catch (Exception e) {
            out.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return out;
    }

    private Object dispatch(ToolCall call, String role) {
        if (!SUPPORTED.contains(call.name())) {
            throw new IllegalArgumentException("未知工具: " + call.name());
        }
        if (ADMIN_ONLY.contains(call.name()) && !"ADMIN".equals(role)) {
            throw new IllegalArgumentException("无权限查看供货价(仅管理员)");
        }
        return switch (call.name()) {
            case "find_product" -> findProduct(call.args());
            case "stock_by_sku" -> stockBySku(call.args());
            case "low_stock" -> lowStock(call.args());
            case "expiring" -> expiring(call.args());
            case "recent_orders" -> recentOrders(call.args());
            case "supplier_quotes" -> supplierQuotes(call.args());
            default -> throw new IllegalArgumentException("未知工具: " + call.name());
        };
    }

    private Map<String, Object> findProduct(Map<String, Object> args) {
        String keyword = str(args, "keyword");
        if (keyword == null) throw new IllegalArgumentException("find_product 需要 keyword 参数");
        int limit = intArg(args, "limit", 5, 1, 10);

        List<Product> found = productService.list(keyword);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < found.size() && i < limit; i++) rows.add(productRow(found.get(i)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", found.size());
        out.put("items", rows);
        return out;
    }

    private Map<String, Object> stockBySku(Map<String, Object> args) {
        String sku = str(args, "sku");
        if (sku == null) throw new IllegalArgumentException("stock_by_sku 需要 sku 参数");

        long unitCount = itemService.count(sku, null, "IN_STOCK", null, null, null, null);
        List<InventoryItem> items = itemService.list(sku, null, "IN_STOCK", null, null, null, null, 1, MAX_ROWS);

        Map<String, Integer> byLocation = new LinkedHashMap<>();
        List<Map<String, Object>> batches = new ArrayList<>();
        for (InventoryItem it : items) {
            byLocation.merge(it.getLocationCode() == null ? "-" : it.getLocationCode(),
                    it.getRemaining() == null ? 0 : it.getRemaining(), Integer::sum);
            if (it.getExpiryDate() != null && batches.size() < MAX_ROWS) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("code", it.getCode());
                row.put("locationCode", it.getLocationCode());
                row.put("remaining", it.getRemaining());
                row.put("expiryDate", it.getExpiryDate().toString());
                batches.add(row);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sku", sku);
        out.put("inStockUnits", unitCount);
        out.put("quantityByLocation", byLocation);
        out.put("batches", batches);
        return out;
    }

    private List<Map<String, Object>> lowStock(Map<String, Object> args) {
        int limit = intArg(args, "limit", 10, 1, 20);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Product p : productService.list(null)) {
            int threshold = p.getLowStock() == null ? 10 : p.getLowStock();
            int qty = p.getQuantity() == null ? 0 : p.getQuantity();
            if (qty > threshold) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sku", p.getSku());
            row.put("name", p.getName());
            row.put("quantity", qty);
            row.put("threshold", threshold);
            rows.add(row);
            if (rows.size() >= limit) break;
        }
        return rows;
    }

    private List<Map<String, Object>> expiring(Map<String, Object> args) {
        String kind = str(args, "kind");
        if (kind == null) kind = "EXPIRING";
        kind = kind.toUpperCase();
        if (!"EXPIRING".equals(kind) && !"EXPIRED".equals(kind)) {
            throw new IllegalArgumentException("kind 只能是 EXPIRING 或 EXPIRED");
        }
        int limit = intArg(args, "limit", 10, 1, 20);
        return dashboardService.expiringItems(kind, limit);
    }

    private List<Map<String, Object>> recentOrders(Map<String, Object> args) {
        String type = str(args, "type");
        if (type == null) type = "ALL";
        type = type.toUpperCase();
        if (!"IN".equals(type) && !"OUT".equals(type) && !"ALL".equals(type)) {
            throw new IllegalArgumentException("type 只能是 IN / OUT / ALL");
        }
        int days = intArg(args, "days", 7, 1, 90);
        int limit = intArg(args, "limit", 5, 1, 10);
        String begin = LocalDate.now().minusDays(days).toString();

        List<Map<String, Object>> rows = new ArrayList<>();
        if ("IN".equals(type) || "ALL".equals(type)) {
            for (InboundOrder o : inboundService.listOrders(begin, null, null, null, null, 1, limit)) {
                rows.add(orderRow("IN", o.getOrderNo(), o.getStatus(), null,
                        o.getTotalQuantity(), o.getCreatedAt()));
            }
        }
        if ("OUT".equals(type) || "ALL".equals(type)) {
            for (OutboundOrder o : outboundService.listOrders(begin, null, null, null, null, 1, limit)) {
                rows.add(orderRow("OUT", o.getOrderNo(), o.getStatus(), o.getCustomer(),
                        o.getTotalQuantity(), o.getCreatedAt()));
            }
        }
        rows.sort(Comparator.comparing(
                (Map<String, Object> r) -> (LocalDateTime) r.get("createdAt"),
                Comparator.nullsLast(Comparator.reverseOrder())));
        return rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows;
    }

    private List<Map<String, Object>> supplierQuotes(Map<String, Object> args) {
        Integer productId = intOrNull(args, "productId");
        String sku = str(args, "sku");
        if (productId == null && sku != null) {
            for (Product p : productService.list(sku)) {
                if (sku.equalsIgnoreCase(p.getSku())) { productId = p.getId(); break; }
            }
        }
        if (productId == null) {
            throw new IllegalArgumentException("supplier_quotes 需要 productId,或能精确匹配的 sku");
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProductSupplier ps : supplierService.listQuotesByProduct(productId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("supplierName", ps.getSupplierName());
            row.put("unitPrice", money(ps.getUnitPrice()));
            row.put("isDefault", ps.getIsDefault());
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> productRow(Product p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sku", p.getSku());
        row.put("name", p.getName());
        row.put("quantity", p.getQuantity());
        row.put("lowStock", p.getLowStock());
        row.put("location", p.getLocation());
        return row;
    }

    private static Map<String, Object> orderRow(String type, String orderNo, String status,
                                                String partner, Integer totalQuantity,
                                                LocalDateTime createdAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type);
        row.put("orderNo", orderNo);
        row.put("status", status);
        row.put("partner", partner);
        row.put("totalQuantity", totalQuantity);
        row.put("createdAt", createdAt);
        return row;
    }

    /** 金额统一两位小数的字符串,避免 double 精度问题,也避免模型把 12.5 说成 12.50 之外的格式 */
    private static String money(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String str(Map<String, Object> args, String key) {
        if (args == null) return null;
        Object v = args.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer intOrNull(Map<String, Object> args, String key) {
        if (args == null) return null;
        Object v = args.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + key + " 不是合法整数: " + s);
        }
    }

    private static int intArg(Map<String, Object> args, String key, int def, int min, int max) {
        Integer v = intOrNull(args, key);
        if (v == null) return def;
        return Math.max(min, Math.min(max, v));
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=AiToolsTest test
```

Expected: PASS(6 个用例)。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/wms/service/ai/ToolCall.java \
        src/main/java/com/wms/service/ai/AiTools.java \
        src/test/java/com/wms/service/ai/AiToolsTest.java
git commit -m "feat(ai): 只读工具集与权限校验"
```

---

### Task 8: 查询计划(解析、校验、降级)

**Files:**
- Create: `src/main/java/com/wms/service/ai/AiPlanner.java`
- Test: `src/test/java/com/wms/service/ai/AiPlannerTest.java`

**Interfaces:**
- Consumes: `AiClient.complete(...)`、`AiTools.SUPPORTED`、`AiSettings.getMaxToolsPerQuestion()`、`ToolCall`
- Produces:
  - `AiPlanner.plan(String question, String contextHint): Plan`,其中 `Plan(List<ToolCall> calls, boolean degraded)`
  - 便于测试的静态方法:`AiPlanner.parsePlan(String raw): List<ToolCall>`、`AiPlanner.keywordFallback(String question): List<ToolCall>`

- [ ] **Step 1: 写失败的测试**

```java
package com.wms.service.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiPlannerTest {

    @Test
    void parses_tools_from_plain_json() {
        List<ToolCall> calls = AiPlanner.parsePlan(
                "{\"tools\":[{\"name\":\"find_product\",\"args\":{\"keyword\":\"洗发水\",\"limit\":3}}]}");

        assertEquals(1, calls.size());
        assertEquals("find_product", calls.get(0).name());
        assertEquals("洗发水", calls.get(0).args().get("keyword"));
        assertEquals(3, calls.get(0).args().get("limit"));
    }

    @Test
    void tolerates_markdown_fence_and_surrounding_prose() {
        List<ToolCall> calls = AiPlanner.parsePlan(
                "好的,这是计划:\n```json\n{\"tools\":[{\"name\":\"low_stock\",\"args\":{}}]}\n```");

        assertEquals("low_stock", calls.get(0).name());
    }

    @Test
    void empty_tool_list_is_valid() {
        assertTrue(AiPlanner.parsePlan("{\"tools\":[]}").isEmpty());
    }

    @Test
    void rejects_unknown_tool_name() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AiPlanner.parsePlan("{\"tools\":[{\"name\":\"delete_all\",\"args\":{}}]}"));
        assertTrue(e.getMessage().contains("未知工具"));
    }

    @Test
    void rejects_non_json_output() {
        assertThrows(IllegalArgumentException.class,
                () -> AiPlanner.parsePlan("我不知道你在问什么。"));
    }

    @Test
    void keyword_fallback_maps_common_questions() {
        assertEquals("expiring", AiPlanner.keywordFallback("这周有哪些商品要过期").get(0).name());
        assertEquals("low_stock", AiPlanner.keywordFallback("哪些该补货了").get(0).name());
        assertEquals("recent_orders", AiPlanner.keywordFallback("最近的出库单有哪些").get(0).name());

        List<ToolCall> find = AiPlanner.keywordFallback("洗发水还有多少库存?");
        assertEquals("find_product", find.get(0).name());
        assertEquals("洗发水", find.get(0).args().get("keyword"));
    }

    @Test
    void plan_falls_back_and_flags_degraded_when_model_is_unusable() {
        AiClient client = mock(AiClient.class);
        when(client.complete(anyString(), any(), anyInt())).thenThrow(new IllegalStateException("boom"));
        AiSettings settings = new AiSettings(true, "openai-compatible", "http://x", "",
                "m", 30000, 3, 500, 6);
        AiPlanner planner = new AiPlanner(client, settings);

        AiPlanner.Plan plan = planner.plan("哪些该补货了", null);

        assertTrue(plan.degraded());
        assertEquals("low_stock", plan.calls().get(0).name());
    }

    @Test
    void plan_truncates_to_configured_max_tools() {
        AiClient client = mock(AiClient.class);
        when(client.complete(anyString(), any(), anyInt())).thenReturn(
                "{\"tools\":[{\"name\":\"low_stock\",\"args\":{}},"
                        + "{\"name\":\"expiring\",\"args\":{}},"
                        + "{\"name\":\"recent_orders\",\"args\":{}},"
                        + "{\"name\":\"find_product\",\"args\":{\"keyword\":\"x\"}}]}");
        AiSettings settings = new AiSettings(true, "openai-compatible", "http://x", "",
                "m", 30000, 3, 500, 6);
        AiPlanner planner = new AiPlanner(client, settings);

        AiPlanner.Plan plan = planner.plan("随便问点什么", null);

        assertTrue(!plan.degraded());
        assertEquals(3, plan.calls().size());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=AiPlannerTest test
```

Expected: 编译失败,`AiPlanner` 找不到符号。

- [ ] **Step 3: 写实现**

```java
package com.wms.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第一轮:让模型只输出 JSON 查询计划,再在后端校验/降级。
 *
 * 为什么不走原生 function calling:换 provider 时各家的 tools 协议差异最大,而"输出一段
 * 受约束 JSON"是所有模型都稳的能力。校验放在后端,模型的不合规输出不会直接变成数据库查询。
 */
@Component
public class AiPlanner {

    private static final Logger log = LoggerFactory.getLogger(AiPlanner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 关键词降级时从问题里剔掉的口语噪声 */
    private static final String[] NOISE = {
            "请问", "麻烦", "帮我", "查一下", "查查", "看一下", "看看", "还有多少", "有多少",
            "多少", "库存", "现在", "目前", "的", "吗", "呢"
    };

    private static final String PLANNER_SYSTEM = """
            你是仓库管理系统的查询规划器。只输出 JSON,不要解释、不要 Markdown 代码块。
            根据用户问题决定要调用哪些工具(0~3 个)。可用工具:
            - find_product: 按关键字查商品;args {keyword: string, limit: 1-10}
            - stock_by_sku: 查某 SKU 的在库分布;args {sku: string}
            - low_stock: 查低于库存阈值的商品;args {limit: 1-20}
            - expiring: 查临期或已过期批次;args {kind: "EXPIRING" | "EXPIRED", limit: 1-20}
            - recent_orders: 查最近出入库单;args {type: "IN" | "OUT" | "ALL", days: 1-90, limit: 1-10}
            - supplier_quotes: 查某商品的供应商供货价(仅管理员);args {productId: int} 或 {sku: string}
            输出格式: {"tools":[{"name":"工具名","args":{...}}]}
            问题不需要查数据时输出 {"tools":[]}。
            """;

    private final AiClient client;
    private final AiSettings settings;

    @Autowired
    public AiPlanner(AiClient client, AiSettings settings) {
        this.client = client;
        this.settings = settings;
    }

    public record Plan(List<ToolCall> calls, boolean degraded) {
    }

    public Plan plan(String question, String contextHint) {
        String user = (contextHint == null || contextHint.isBlank())
                ? "问题:" + question
                : "问题:" + question + "\n用户当前所在页面:" + contextHint;
        try {
            String raw = client.complete(PLANNER_SYSTEM, List.of(ChatMessage.user(user)), 512);
            List<ToolCall> calls = parsePlan(raw);
            int max = settings.getMaxToolsPerQuestion();
            if (calls.size() > max) calls = new ArrayList<>(calls.subList(0, max));
            return new Plan(calls, false);
        } catch (Exception e) {
            log.warn("AI 查询计划不可用,降级到关键词匹配: {}", e.getMessage());
            return new Plan(keywordFallback(question), true);
        }
    }

    static List<ToolCall> parsePlan(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("模型输出为空");
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        int l = s.indexOf('{');
        int r = s.lastIndexOf('}');
        if (l < 0 || r <= l) throw new IllegalArgumentException("模型输出不是 JSON 对象");

        JsonNode root;
        try {
            root = MAPPER.readTree(s.substring(l, r + 1));
        } catch (Exception e) {
            throw new IllegalArgumentException("模型输出不是合法 JSON");
        }
        JsonNode arr = root.get("tools");
        if (arr == null || !arr.isArray()) throw new IllegalArgumentException("JSON 缺少 tools 数组");

        List<ToolCall> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String name = n.path("name").asText("");
            if (!AiTools.SUPPORTED.contains(name)) throw new IllegalArgumentException("未知工具: " + name);
            Map<String, Object> args = new LinkedHashMap<>();
            JsonNode a = n.get("args");
            if (a != null && a.isObject()) {
                a.fields().forEachRemaining(e -> args.put(e.getKey(), plainValue(e.getValue())));
            }
            out.add(new ToolCall(name, args));
        }
        return out;
    }

    /** 大幅坏 JSON 时才走这里:用关键词猜一个最可能的工具,保证用户至少拿到点数据。 */
    static List<ToolCall> keywordFallback(String question) {
        String q = question == null ? "" : question;
        if (anyOf(q, "过期", "到期", "效期", "临期")) {
            return List.of(new ToolCall("expiring",
                    Map.of("kind", q.contains("已过期") ? "EXPIRED" : "EXPIRING")));
        }
        if (anyOf(q, "补货", "低库存", "库存低", "缺货", "断货")) {
            return List.of(new ToolCall("low_stock", Map.of()));
        }
        if (anyOf(q, "供货价", "报价", "进货价", "供货")) {
            String kw = keyword(q);
            return List.of(new ToolCall("supplier_quotes",
                    kw.isEmpty() ? Map.of() : Map.of("sku", kw)));
        }
        if (anyOf(q, "出库单", "入库单", "单据", "订单")) {
            return List.of(new ToolCall("recent_orders", Map.of()));
        }
        String kw = keyword(q);
        return kw.isEmpty() ? List.of() : List.of(new ToolCall("find_product", Map.of("keyword", kw)));
    }

    private static boolean anyOf(String q, String... words) {
        for (String w : words) if (q.contains(w)) return true;
        return false;
    }

    private static String keyword(String q) {
        String s = q;
        for (String n : NOISE) s = s.replace(n, "");
        s = s.replaceAll("[\\s，,。.！!？?:：、\"']", "");
        return s.length() > 30 ? s.substring(0, 30) : s;
    }

    private static Object plainValue(JsonNode v) {
        if (v.isTextual()) return v.asText();
        if (v.isIntegralNumber()) return v.asInt();
        if (v.isBoolean()) return v.asBoolean();
        if (v.isFloatingPointNumber()) return v.asDouble();
        return v.toString();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=AiPlannerTest test
```

Expected: PASS(8 个用例)。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/wms/service/ai/AiPlanner.java src/test/java/com/wms/service/ai/AiPlannerTest.java
git commit -m "feat(ai): 查询计划解析与降级"
```

---

### Task 9: 编排服务(两轮 + 降级 + 错误)

**Files:**
- Create: `src/main/java/com/wms/service/ai/AiChatService.java`
- Test: `src/test/java/com/wms/service/ai/AiChatServiceTest.java`

**Interfaces:**
- Consumes: `AiPlanner.plan(...)`、`AiTools.execute(...)`、`AiClient.stream(...)`、`AiSettings.getMaxQuestionLength()`
- Produces:
  - `AiChatService.ChatSink` 接口:`meta(Meta)` / `delta(String)` / `error(String)` / `done()`
  - `AiChatService.Meta(List<String> tools, boolean degraded, String warning)`
  - `AiChatService.chat(String question, String locale, String contextHint, String role, ChatSink sink): void`

> **注意:这里刻意不加 `@Transactional`。** 方法里包含一次耗时数秒的外部模型调用;
> 挂事务会把数据库连接一直攥在手里,比不加更糟。工具内部的只读方法各自带事务。

- [ ] **Step 1: 写失败的测试**

```java
package com.wms.service.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiChatServiceTest {

    private final AiClient client = mock(AiClient.class);
    private final AiPlanner planner = mock(AiPlanner.class);
    private final AiTools tools = mock(AiTools.class);
    private final AiSettings settings = new AiSettings(true, "openai-compatible", "http://x", "",
            "m", 30000, 3, 500, 6);
    private final AiChatService service = new AiChatService(client, planner, tools, settings);

    /** 收集回调顺序,便于断言"先 meta 再 delta 最后 done" */
    private static final class Recorder implements AiChatService.ChatSink {
        final StringBuilder events = new StringBuilder();
        String error;
        String warning;
        boolean degraded;

        public void meta(AiChatService.Meta m) {
            events.append("meta:").append(String.join("|", m.tools())).append(' ');
            degraded = m.degraded();
            warning = m.warning();
        }
        public void delta(String text) { events.append("delta:").append(text).append(' '); }
        public void error(String message) { error = message; }
        public void done() { events.append("done"); }
    }

    @Test
    void emits_meta_then_deltas_then_done() {
        when(planner.plan("洗发水还有多少", null))
                .thenReturn(new AiPlanner.Plan(List.of(new ToolCall("find_product",
                        Map.of("keyword", "洗发水"))), false));
        when(tools.execute(any(), anyString())).thenReturn(Map.of("tool", "find_product", "result", Map.of()));
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.function.Consumer<String> cb = inv.getArgument(3);
            cb.accept("共 128 ");
            cb.accept("件");
            return null;
        }).when(client).stream(anyString(), any(), anyInt(), any());

        Recorder rec = new Recorder();
        service.chat("洗发水还有多少", "zh-CN", null, "OPERATOR", rec);

        assertEquals("meta:find_product delta:共 128 delta:件 done", rec.events.toString());
        assertNull(rec.error);
    }

    @Test
    void oversized_tool_payload_is_capped_before_it_reaches_the_model() {
        when(planner.plan(any(), any())).thenReturn(new AiPlanner.Plan(
                List.of(new ToolCall("find_product", Map.of("keyword", "x"))), false));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) big.append("xxxxxxxxxx");
        when(tools.execute(any(), anyString())).thenReturn(
                Map.of("tool", "find_product", "result", big.toString()));

        Recorder rec = new Recorder();
        service.chat("x", "zh-CN", null, "OPERATOR", rec);

        org.mockito.ArgumentCaptor<java.util.List<ChatMessage>> cap =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(client).stream(anyString(), cap.capture(), anyInt(), any());
        assertTrue(cap.getValue().get(0).content().length() < 4200);
    }

    @Test
    void degraded_plan_is_surfaced_to_the_caller() {
        when(planner.plan(any(), any()))
                .thenReturn(new AiPlanner.Plan(List.of(new ToolCall("low_stock", Map.of())), true));
        when(tools.execute(any(), anyString())).thenReturn(Map.of("tool", "low_stock"));

        Recorder rec = new Recorder();
        service.chat("哪些该补货了", "zh-CN", null, "OPERATOR", rec);

        assertTrue(rec.degraded);
    }

    @Test
    void over_long_question_is_truncated_with_a_warning() {
        String longQ = "洗".repeat(800);
        when(planner.plan(anyString(), any()))
                .thenReturn(new AiPlanner.Plan(List.of(), false));

        Recorder rec = new Recorder();
        service.chat(longQ, "zh-CN", null, "OPERATOR", rec);

        assertTrue(rec.warning.contains("500"));
        verify(planner).plan("洗".repeat(500), null);
    }

    @Test
    void model_failure_becomes_an_error_event_not_an_exception() {
        when(planner.plan(any(), any())).thenReturn(new AiPlanner.Plan(List.of(), false));
        doThrow(new IllegalStateException("down")).when(client).stream(anyString(), any(), anyInt(), any());

        Recorder rec = new Recorder();
        service.chat("你好", "zh-CN", null, "OPERATOR", rec);

        assertEquals("AI 服务暂时不可用,请稍后重试", rec.error);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=AiChatServiceTest test
```

Expected: 编译失败,`AiChatService` 找不到符号。

- [ ] **Step 3: 写实现**

```java
package com.wms.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一问一答的编排:计划 → 取数 → 生成。不含事务(见类内说明),不落库。
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 单次工具结果交给模型的字符上限,超过就截断 —— 防止一次查询把上下文撑爆、拖慢回答 */
    private static final int MAX_PAYLOAD_CHARS = 4000;

    private static final String ANSWER_SYSTEM_ZH = """
            你是仓库管理系统的助手。你只能依据下面给出的"已取到的数据"回答,数据里没有的信息就说不知道,
            不要编造数字。金额保留两位小数。给出结论后可以补一句具体建议(补货 / 先出哪批 / 差异怎么处理),
            建议要落到数字上。回答用中文,控制在 300 字以内。
            """;

    private static final String ANSWER_SYSTEM_EN = """
            You are an assistant for a warehouse management system. Answer ONLY from the data provided below;
            if the data does not contain the answer, say so. Never invent numbers. Keep money to two decimals.
            After the conclusion you may add one concrete recommendation grounded in the numbers, in English,
            under 300 words.
            """;

    public interface ChatSink {
        void meta(Meta meta);
        void delta(String text);
        void error(String message);
        void done();
    }

    public record Meta(List<String> tools, boolean degraded, String warning) {
    }

    private final AiClient client;
    private final AiPlanner planner;
    private final AiTools tools;
    private final AiSettings settings;

    @Autowired
    public AiChatService(AiClient client, AiPlanner planner, AiTools tools, AiSettings settings) {
        this.client = client;
        this.planner = planner;
        this.tools = tools;
        this.settings = settings;
    }

    public void chat(String question, String locale, String contextHint, String role, ChatSink sink) {
        long start = System.currentTimeMillis();

        String q = sanitize(question);
        if (q.isEmpty()) throw new IllegalArgumentException("问题不能为空");
        String warning = null;
        int max = settings.getMaxQuestionLength();
        if (q.length() > max) {
            q = q.substring(0, max);
            warning = "问题过长,已截断到 " + max + " 字";
        }

        AiPlanner.Plan plan = planner.plan(q, contextHint);

        List<Map<String, Object>> results = new ArrayList<>();
        for (ToolCall call : plan.calls()) results.add(tools.execute(call, role));

        sink.meta(new Meta(plan.calls().stream().map(ToolCall::name).toList(),
                plan.degraded(), warning));

        try {
            String system = "en-US".equals(locale) ? ANSWER_SYSTEM_EN : ANSWER_SYSTEM_ZH;
            String payload;
            try {
                payload = MAPPER.writeValueAsString(results);
            } catch (Exception e) {
                payload = "[]";
            }
            if (payload.length() > MAX_PAYLOAD_CHARS) {
                payload = payload.substring(0, MAX_PAYLOAD_CHARS) + "…(已截断)";
            }
            String user = "用户问题:" + q + "\n\n已取到的数据(JSON):\n" + payload;
            client.stream(system, List.of(ChatMessage.user(user)), 1024, sink::delta);
            sink.done();
        } catch (Exception e) {
            log.warn("AI 回答生成失败(耗时 {}ms): {}", System.currentTimeMillis() - start, e.getMessage());
            sink.error("AI 服务暂时不可用,请稍后重试");
            return;
        }

        // 只记元信息:工具名、是否降级、耗时。问题与答案内容不进日志。
        log.info("AI 问答完成:工具={} degraded={} 耗时={}ms",
                plan.calls().stream().map(ToolCall::name).toList(),
                plan.degraded(), System.currentTimeMillis() - start);
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\p{Cntrl}]", "").trim();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=AiChatServiceTest test
```

Expected: PASS(5 个用例)。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/wms/service/ai/AiChatService.java src/test/java/com/wms/service/ai/AiChatServiceTest.java
git commit -m "feat(ai): 问答编排与错误降级"
```

---

### Task 10: HTTP 接口(SSE + 状态)

**Files:**
- Create: `src/main/java/com/wms/model/ai/AiChatRequest.java`
- Create: `src/main/java/com/wms/controller/AiChatController.java`
- Test: `src/test/java/com/wms/controller/AiChatControllerTest.java`

**Interfaces:**
- Consumes: `AiChatService.chat(...)`、`AiSettings`、`AiThrottle.check(int)`
- Produces:
  - `GET /api/ai/status` → `ApiResponse<Map<String,Object>>`,键 `enabled/provider/model`
  - `POST /api/ai/chat` → `SseEmitter`,事件 `meta` / `delta` / `error` / `done`

- [ ] **Step 1: 写失败的测试**

```java
package com.wms.controller;

import com.wms.model.ApiResponse;
import com.wms.service.ai.AiChatService;
import com.wms.service.ai.AiSettings;
import com.wms.service.ai.AiThrottle;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiChatControllerTest {

    /** 同步执行器:让异步分支在测试里立刻跑完 */
    private static ExecutorService direct() {
        return new AbstractExecutorService() {
            public void execute(Runnable r) { r.run(); }
            public void shutdown() { }
            public java.util.List<Runnable> shutdownNow() { return java.util.List.of(); }
            public boolean isShutdown() { return false; }
            public boolean isTerminated() { return false; }
            public boolean awaitTermination(long t, TimeUnit u) { return true; }
        };
    }

    private static AiSettings enabled() {
        return new AiSettings(true, "openai-compatible", "http://x", "", "m", 30000, 3, 500, 6);
    }

    private MockHttpServletRequest authed(String role) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute("userId", 7);
        r.setAttribute("role", role);
        return r;
    }

    @Test
    void status_reports_enabled_provider_and_model_without_secrets() {
        AiChatController c = new AiChatController(mock(AiChatService.class), enabled(),
                mock(AiThrottle.class), direct());

        ApiResponse<Map<String, Object>> res = c.status();

        assertEquals(true, res.getData().get("enabled"));
        assertEquals("openai-compatible", res.getData().get("provider"));
        assertEquals("m", res.getData().get("model"));
        assertEquals(3, res.getData().size());
    }

    @Test
    void disabled_feature_hides_chat_behind_404() {
        AiSettings off = new AiSettings(false, "openai-compatible", "http://x", "", "m", 30000, 3, 500, 6);
        AiChatController c = new AiChatController(mock(AiChatService.class), off,
                mock(AiThrottle.class), direct());

        assertThrows(NotFoundException.class, () -> c.chat(request("你好"), authed("OPERATOR")));
    }

    @Test
    void blank_question_is_rejected_before_any_model_call() {
        AiChatService service = mock(AiChatService.class);
        AiChatController c = new AiChatController(service, enabled(), mock(AiThrottle.class), direct());

        assertThrows(IllegalArgumentException.class, () -> c.chat(request("   "), authed("OPERATOR")));
        verify(service, never()).chat(any(), any(), any(), any(), any());
    }

    @Test
    void chat_passes_role_from_the_jwt_attributes() {
        AiChatService service = mock(AiChatService.class);
        AiThrottle throttle = mock(AiThrottle.class);
        AiChatController c = new AiChatController(service, enabled(), throttle, direct());

        c.chat(request("哪些该补货了"), authed("ADMIN"));

        verify(throttle).check(7);
        verify(service).chat(eq("哪些该补货了"), eq("zh-CN"), any(), eq("ADMIN"), any());
    }

    private static com.wms.model.ai.AiChatRequest request(String question) {
        com.wms.model.ai.AiChatRequest r = new com.wms.model.ai.AiChatRequest();
        r.setQuestion(question);
        return r;
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=AiChatControllerTest test
```

Expected: 编译失败,`AiChatController` / `AiChatRequest` 找不到符号。

- [ ] **Step 3: 写实现**

`src/main/java/com/wms/model/ai/AiChatRequest.java`:

```java
package com.wms.model.ai;

import java.util.Map;

/** /api/ai/chat 的请求体。全部字段可选性由 controller 校验。 */
public class AiChatRequest {

    private String question;
    private String locale;                   // zh-CN | en-US
    private Map<String, Object> routeContext;  // 前端当前页面与筛选条件,仅作提示

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }

    public Map<String, Object> getRouteContext() { return routeContext; }
    public void setRouteContext(Map<String, Object> routeContext) { this.routeContext = routeContext; }
}
```

`src/main/java/com/wms/controller/AiChatController.java`:

```java
package com.wms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.model.ApiResponse;
import com.wms.model.ai.AiChatRequest;
import com.wms.service.ai.AiChatService;
import com.wms.service.ai.AiSettings;
import com.wms.service.ai.AiThrottle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 助手入口。
 *
 * 两个接口的开关语义不同:status **始终可用**(前端靠它决定显不显示入口),
 * chat 在关闭时返回 404。
 *
 * 流内错误走 SSE 的 error 事件而不是 HTTP 状态码 —— 流一旦开始,状态码就改不了了。
 */
@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiChatService chatService;
    private final AiSettings settings;
    private final AiThrottle throttle;
    private final ExecutorService pool;

    @Autowired
    public AiChatController(AiChatService chatService, AiSettings settings, AiThrottle throttle) {
        this(chatService, settings, throttle, Executors.newFixedThreadPool(4));
    }

    /** 测试用:注入同步执行器 */
    AiChatController(AiChatService chatService, AiSettings settings, AiThrottle throttle,
                     ExecutorService pool) {
        this.chatService = chatService;
        this.settings = settings;
        this.throttle = throttle;
        this.pool = pool;
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", settings.isEnabled());
        m.put("provider", settings.getProvider());
        m.put("model", settings.getModel());
        return ApiResponse.ok(m);
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody AiChatRequest body, HttpServletRequest request) {
        if (!settings.isEnabled()) throw new NotFoundException("AI 助手未启用");

        String question = body == null ? null : body.getQuestion();
        if (question == null || question.isBlank()) throw new IllegalArgumentException("问题不能为空");

        Integer userId = (Integer) request.getAttribute("userId");
        String role = (String) request.getAttribute("role");
        throttle.check(userId == null ? 0 : userId);

        SseEmitter emitter = new SseEmitter((long) settings.getTimeoutMs() * 2);
        String locale = body.getLocale() == null ? "zh-CN" : body.getLocale();
        String contextHint = contextHint(body.getRouteContext());

        AiChatService.ChatSink sink = new AiChatService.ChatSink() {
            public void meta(AiChatService.Meta m) { send(emitter, "meta", m); }
            public void delta(String text) { send(emitter, "delta", Map.of("text", text)); }
            public void error(String message) {
                send(emitter, "error", Map.of("message", message));
                emitter.complete();
            }
            public void done() {
                send(emitter, "done", Map.of());
                emitter.complete();
            }
        };

        pool.execute(() -> {
            try {
                chatService.chat(question, locale, contextHint, role, sink);
            } catch (IllegalArgumentException e) {
                sink.error(e.getMessage());
            } catch (Exception e) {
                log.warn("AI 问答异常: {}", e.getClass().getSimpleName());
                sink.error("AI 服务暂时不可用,请稍后重试");
            }
        });
        return emitter;
    }

    /** 把页面上下文压成一行给模型的提示,失败就当作没有。 */
    private static String contextHint(Map<String, Object> ctx) {
        if (ctx == null || ctx.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(ctx);
        } catch (Exception e) {
            return null;
        }
    }

    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException | IllegalStateException e) {
            // 客户端断开:忽略。让它冒出来只会在线程池里堆异常日志。
        }
    }
}
```

同时修正测试里那处 `c.service()` 占位:把第一个用例改成持有 `AiChatService service = mock(...)` 变量,
断言写 `verify(service, never()).chat(...)`。

- [ ] **Step 4: 跑测试确认通过**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo -Dtest=AiChatControllerTest test
```

Expected: PASS(4 个用例)。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/wms/model/ai/AiChatRequest.java \
        src/main/java/com/wms/controller/AiChatController.java \
        src/test/java/com/wms/controller/AiChatControllerTest.java
git commit -m "feat(ai): SSE 接口与状态接口"
```

---

### Task 11: 配置接线与文档

**Files:**
- Modify: `src/main/resources/application.yml`(末尾追加 `wms.ai.*`)
- Modify: `.env.example`
- Modify: `README.md`(功能表 + 接口表 + 一段配置说明)
- Modify: `docs/superpowers/specs/2026-11-05-ai-assistant-design.md`(§8 的 `enabled` 默认值)

**Interfaces:**
- Consumes: `AiSettings` 读取的 9 个键(见 Task 1)
- Produces: 可运行的配置面 + 文档

- [ ] **Step 1: application.yml 追加配置**

在 `wms:` 段末尾(`inbound.import.max-units` 之后)追加:

```yaml
  ai:
    # AI 助手默认关闭:没配模型地址时前端不该出现入口。
    # 配好 WMS_AI_BASE_URL 之后把 WMS_AI_ENABLED 设为 true 即可。
    enabled: ${WMS_AI_ENABLED:false}
    provider: ${WMS_AI_PROVIDER:openai-compatible}   # openai-compatible | anthropic
    base-url: ${WMS_AI_BASE_URL:http://localhost:11434/v1}
    api-key: ${WMS_AI_API_KEY:}                      # anthropic 必填;ollama 可空
    model: ${WMS_AI_MODEL:qwen2.5:7b}
    timeout-ms: 30000                                # 建连超时;流式不设整段超时
    max-tools-per-question: 3
    max-question-length: 500
    rate-limit-per-minute: 6
```

> 注意 YAML 缩进:这是 `wms:` 下的 `ai:` 子键,和 `low-stock-threshold` / `import` / `inbound` 同级。

- [ ] **Step 2: 同步修正设计文档的默认值**

设计文档 §8 写的是 `enabled: true`,实现改为默认 `false`(没有模型地址时按钮不该出现)。
把该行改成:

```yaml
    enabled: ${WMS_AI_ENABLED:false}   # 配好模型地址后再打开
```

- [ ] **Step 3: .env.example 追加**

```bash
# ---- AI 助手(可选) ----
# provider=openai-compatible 时指向 Ollama / vLLM / DeepSeek 的 OpenAI 兼容端点;
# provider=anthropic 时指向 https://api.anthropic.com,并必须填 WMS_AI_API_KEY。
WMS_AI_ENABLED=false
WMS_AI_BASE_URL=http://localhost:11434/v1
WMS_AI_API_KEY=
WMS_AI_MODEL=qwen2.5:7b
```

- [ ] **Step 4: README 追加**

在「✨ 功能模块」表末尾加一行:

```markdown
| 🤖 AI 助手 | 右下角悬浮球唤起;自然语言问库存 / 效期 / 单据 / 补货;只读取数、按当前用户权限;模型 provider 可切换 |
```

在「📡 REST 接口一览」的「库存与盘点」表之后新增一节:

```markdown
### AI 助手(可选,默认关闭)

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| GET | `/api/ai/status` | 是否启用 / provider / model;始终可用,前端据此决定是否显示入口 |
| POST | `/api/ai/chat` | 自然语言问答,SSE 流式返回;`enabled=false` 时 404 |
```

在「⚙️ 低库存阈值」一节之后新增:

```markdown
## 🤖 AI 助手

默认**关闭**。启用需要一台运行模型服务的机器(Ollama 即可),然后设置环境变量:

```bash
WMS_AI_ENABLED=true
WMS_AI_BASE_URL=http://<模型机IP>:11434/v1
WMS_AI_MODEL=qwen2.5:7b
```

切到云端 Claude:`WMS_AI_PROVIDER=anthropic`、`WMS_AI_BASE_URL=https://api.anthropic.com`、
`WMS_AI_MODEL=claude-opus-4-8`、并填 `WMS_AI_API_KEY`。**注意:选了云端 provider,
商品 / 供应商 / 价格等业务数据会离开局域网** —— 是否可接受属于部署方的合规判断。

助手只读:不会建单、不会改库存。取数按当前登录用户的角色执行,OPERATOR 看不到供应商供货价。
```

- [ ] **Step 5: 编译并跑全量测试**

```bash
C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo test
```

Expected: 全部 PASS(包括既有测试)。

- [ ] **Step 6: 提交**

```bash
git add src/main/resources/application.yml .env.example README.md \
        docs/superpowers/specs/2026-11-05-ai-assistant-design.md
git commit -m "docs(ai): 配置项与环境变量说明"
```

---

### Task 12: 前端悬浮球与抽屉

**Files:**
- Create: `frontend/js/assistant.js`
- Modify: `frontend/index.html`(悬浮球 + 抽屉 + `<script>`)
- Modify: `frontend/css/app.css`(追加样式)
- Modify: `frontend/js/i18n.js`(追加 `ai.*` 文案)

**Interfaces:**
- Consumes: `GET /api/ai/status`、`POST /api/ai/chat`(Task 10)、`Auth.getToken()`、`I18N.getLang()`、`t(key)`
- Produces: 全局 `Assistant`(`open()/close()/ask()`),页面加载后自动初始化

- [ ] **Step 1: 追加 i18n 文案**

在 `frontend/js/i18n.js` 的字典里、`'nav.dashboard'` 那一行之前插入:

```js
        /* ---------- AI 助手 ---------- */
        'ai.title':        { 'zh-CN': 'AI 助手',            'en-US': 'AI Assistant' },
        'ai.open':         { 'zh-CN': '打开 AI 助手',        'en-US': 'Open AI Assistant' },
        'ai.placeholder':  { 'zh-CN': '问问库存、效期或单据…', 'en-US': 'Ask about stock, expiry, or orders…' },
        'ai.send':         { 'zh-CN': '发送',                'en-US': 'Send' },
        'ai.thinking':     { 'zh-CN': '正在查询…',           'en-US': 'Looking up…' },
        'ai.error':        { 'zh-CN': 'AI 服务暂时不可用,请稍后重试', 'en-US': 'AI service is unavailable, please try again later' },
```

- [ ] **Step 2: index.html 加节点与脚本**

在 `</header>` 之后、`<main id="view">` 之前插入:

```html
<!-- AI 助手(默认隐藏,status 返回 enabled=true 时才显示) -->
<button id="ai-fab" class="ai-fab" data-i18n-title="ai.open" title="AI 助手" hidden>🤖</button>
<div id="ai-drawer" class="ai-drawer" hidden>
    <div class="ai-drawer-head">
        <span data-i18n="ai.title">AI 助手</span>
        <button class="icon-btn" data-i18n-title="common.close" title="关闭" onclick="Assistant.close()">×</button>
    </div>
    <div class="ai-messages" id="ai-messages"></div>
    <div class="ai-input-row">
        <input id="ai-input" type="text" maxlength="500"
               data-i18n-placeholder="ai.placeholder" placeholder="问问库存、效期或单据…">
        <button class="btn btn-primary btn-sm" id="ai-send" data-i18n="ai.send">发送</button>
    </div>
</div>
```

在脚本区、`api.js` 之后、`app.js` 之前插入:

```html
<script src="/js/assistant.js"></script>
```

- [ ] **Step 3: 写 assistant.js**

```js
/* ============================================
 *  assistant.js - AI 助手:悬浮球 + 右侧抽屉
 *  只读问答;对话仅存在内存,刷新即清空。
 * ============================================ */

const Assistant = (() => {
    let busy = false;
    let warnedLong = false;

    /** 页面加载时问一次后端:开没开。开才显示入口。 */
    async function init() {
        if (!Auth.getToken()) return;              // 登录页/未登录不显示
        try {
            const st = await api.get('/api/ai/status');
            if (!st || !st.enabled) return;
            document.getElementById('ai-fab').hidden = false;
        } catch (e) { /* 状态取不到就当没开,不打扰用户 */ }
    }

    function open() {
        const drawer = document.getElementById('ai-drawer');
        drawer.hidden = false;
        document.getElementById('ai-input').focus();
    }

    function close() {
        document.getElementById('ai-drawer').hidden = true;
    }

    async function ask() {
        const input = document.getElementById('ai-input');
        const question = (input.value || '').trim();
        if (!question || busy) return;

        input.value = '';
        append('user', question);
        const bubble = append('bot', '');
        bubble.classList.add('thinking');
        bubble.textContent = t('ai.thinking');

        busy = true;
        let gotText = false;
        spinner(bubble);

        try {
            await stream(question, {
                onMeta(m) {
                    if (m && m.warning && !warnedLong) {
                        warnedLong = true;
                        toast(m.warning);
                    }
                },
                onDelta(text) {
                    if (!gotText) { bubble.textContent = ''; gotText = true; }
                    bubble.textContent += text;
                    scrollToEnd();
                },
                onError(message) {
                    bubble.textContent = message;
                    toast(message, 'error');
                },
                onDone() { scrollToEnd(); }
            });
        } catch (e) {
            bubble.textContent = e.message || t('ai.error');
        } finally {
            stopSpinner(bubble);
            busy = false;
        }
    }

    /**
     * SSE 必须用 fetch 手写解析:EventSource 带不了 Authorization 头,会 401。
     * 后端格式见 AiChatController:event: meta|delta|error|done + data: <json>
     */
    async function stream(question, handlers) {
        const res = await fetch('/api/ai/chat', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': 'Bearer ' + Auth.getToken()
            },
            body: JSON.stringify({
                question,
                locale: I18N.getLang(),
                routeContext: { route: (location.hash || '#/dashboard').replace('#/', '') }
            })
        });

        if (res.status === 401) {
            Auth.clearToken();
            location.href = '/login.html';
            return;
        }
        if (!res.ok) {
            let message = 'HTTP ' + res.status;
            try { const j = await res.json(); if (j.message) message = j.message; } catch (e) { /* 非 JSON */ }
            throw new Error(message);
        }

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });

            // SSE 以空行分帧;最后一段可能还没收完,留在 buffer 里
            const frames = buffer.split('\n\n');
            buffer = frames.pop();
            frames.forEach(f => dispatch(f, handlers));
        }
        if (buffer.trim()) dispatch(buffer, handlers);
    }

    function dispatch(frame, handlers) {
        let name = 'message';
        const dataLines = [];
        for (const line of frame.split('\n')) {
            if (line.startsWith('event:')) name = line.slice(6).trim();
            else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
        }
        if (!dataLines.length) return;

        let data = {};
        try { data = JSON.parse(dataLines.join('\n')); } catch (e) { return; }

        if (name === 'meta') handlers.onMeta(data);
        else if (name === 'delta') handlers.onDelta(data.text || '');
        else if (name === 'error') handlers.onError(data.message || t('ai.error'));
        else if (name === 'done') handlers.onDone();
    }

    function bubble(role) {
        const div = document.createElement('div');
        div.className = 'ai-msg ' + (role === 'user' ? 'user' : 'bot');
        document.getElementById('ai-messages').appendChild(div);
        return div;
    }

    function append(role, text) {
        const div = bubble(role);
        div.textContent = text;
        scrollToEnd();
        return div;
    }

    function scrollToEnd() {
        const box = document.getElementById('ai-messages');
        box.scrollTop = box.scrollHeight;
    }

    /** 等模型时给个省略号动画,避免用户以为卡住了 */
    let spinTimer = null;
    function spinner(el) {
        let dots = 0;
        clearInterval(spinTimer);
        spinTimer = setInterval(() => {
            if (!el.classList.contains('thinking')) return;
            dots = (dots + 1) % 4;
            el.textContent = t('ai.thinking').replace('…', '') + '.'.repeat(dots);
        }, 400);
    }
    function stopSpinner(el) {
        el.classList.remove('thinking');
        clearInterval(spinTimer);
        spinTimer = null;
    }

    window.addEventListener('DOMContentLoaded', () => {
        init();
        document.getElementById('ai-fab').addEventListener('click', open);
        document.getElementById('ai-send').addEventListener('click', ask);
        document.getElementById('ai-input').addEventListener('keydown', e => {
            if (e.key === 'Enter') { e.preventDefault(); ask(); }
        });
        document.addEventListener('keydown', e => {
            if (e.key === 'Escape') close();
        });
    });

    return { init, open, close, ask };
})();
```

- [ ] **Step 4: app.css 追加样式**

```css
/* ---------- AI 助手 ---------- */
.ai-fab {
    position: fixed; right: 24px; bottom: 24px; z-index: 60;
    width: 52px; height: 52px; border: 0; border-radius: 50%;
    font-size: 24px; cursor: pointer; background: #fff;
    box-shadow: 0 6px 18px rgba(0, 0, 0, .18);
}
.ai-drawer {
    position: fixed; top: 0; right: 0; z-index: 70;
    width: 380px; max-width: 100vw; height: 100vh;
    display: flex; flex-direction: column;
    background: #fff; box-shadow: -8px 0 24px rgba(0, 0, 0, .12);
}
.ai-drawer-head {
    display: flex; align-items: center; justify-content: space-between;
    padding: 12px 16px; border-bottom: 1px solid #eee; font-weight: 600;
}
.ai-messages {
    flex: 1; overflow-y: auto; padding: 12px 16px;
    display: flex; flex-direction: column; gap: 10px;
}
.ai-msg {
    max-width: 85%; padding: 8px 12px; border-radius: 10px;
    font-size: 14px; line-height: 1.5;
    white-space: pre-wrap; word-break: break-word;
}
.ai-msg.user { align-self: flex-end; background: #e8f1ff; }
.ai-msg.bot  { align-self: flex-start; background: #f5f5f5; }
.ai-input-row {
    display: flex; gap: 8px; padding: 12px 16px; border-top: 1px solid #eee;
}
.ai-input-row input {
    flex: 1; padding: 8px 10px; border: 1px solid #ddd; border-radius: 8px; font-size: 14px;
}
@media (max-width: 720px) {
    .ai-drawer { width: 100vw; }
}
```

- [ ] **Step 5: 手工验证**

1. 不起后端也能确认结构:浏览器打开 `http://localhost:9080/`(需要 Docker frontend 在跑),
   控制台检查 `Assistant` 已定义、`#ai-fab` 仍是 `hidden`(因为 status 拿不到或未启用)。
2. 按 Task 11 配好 `WMS_AI_*` 并重建前端 / 后端后,悬浮球出现;点开抽屉,问"洗发水还有多少库存",
   应看到逐字追加的回答。
3. 把 `WMS_AI_BASE_URL` 指向一个不存在的地址,再问一句,应看到红色 toast 与气泡里的错误文案,
   界面不卡住。
4. 用 OPERATOR 账号问"某某供应商的供货价",回答里应说明无权限,不出现价格。

- [ ] **Step 6: 提交**

```bash
git add frontend/js/assistant.js frontend/index.html frontend/css/app.css frontend/js/i18n.js
git commit -m "feat(ai): 前端悬浮球与对话抽屉"
```

---

## 验证清单(全部任务完成后)

- [ ] `C:/apache-maven-3.9.6/bin/mvn.cmd -q -o -Dmaven.repo.local=.m2-repo test` 全绿
- [ ] `WMS_AI_ENABLED=false` 时:`/api/ai/status` 返回 `enabled:false`、`/api/ai/chat` 返回 404、前端无悬浮球
- [ ] `WMS_AI_ENABLED=true` + Ollama 时:库存 / 临期 / 补货三类问题答案与页面数字一致
- [ ] OPERATOR 查供货价被拒;ADMIN 能查到
- [ ] 连续快速点击 7 次,第 7 次收到 429 提示
- [ ] 后端日志里搜不到任何商品名、供应商名或金额

## 与设计文档的偏差记录

| 项 | 设计文档 | 实现 | 原因 |
| ---- | ---- | ---- | ---- |
| `wms.ai.enabled` 默认值 | `true` | `${WMS_AI_ENABLED:false}` | 没有模型地址时默认开启会让用户点了就报错;默认关、配好了再开 |
| `AiChatService` 事务 | 未提及 | 刻意不加 `@Transactional` | 方法内含数秒的外部调用,挂事务会长时间占住数据库连接 |
| 多轮上下文 | "只带最近 4 轮对话" | 每次提问独立,不带上下文 | 要真支持多轮,前后端各要传一份历史并处理注入风险;第一版先不做,已列入后续 |

<!-- PART-END-5 -->




