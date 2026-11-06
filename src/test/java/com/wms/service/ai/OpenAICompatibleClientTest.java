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
