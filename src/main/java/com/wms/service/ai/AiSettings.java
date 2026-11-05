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
