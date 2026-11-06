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
