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
