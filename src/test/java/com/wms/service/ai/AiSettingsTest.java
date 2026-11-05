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
