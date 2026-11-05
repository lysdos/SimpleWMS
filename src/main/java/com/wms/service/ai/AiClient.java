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
