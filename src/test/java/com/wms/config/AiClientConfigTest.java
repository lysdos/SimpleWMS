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
}
