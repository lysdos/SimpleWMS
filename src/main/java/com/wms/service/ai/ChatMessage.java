package com.wms.service.ai;

/** 一条对话消息。role 只用 "user" / "assistant"(system 走独立参数,不混进列表)。 */
public record ChatMessage(String role, String content) {

    public static ChatMessage user(String content) { return new ChatMessage("user", content); }
    public static ChatMessage assistant(String content) { return new ChatMessage("assistant", content); }
}
