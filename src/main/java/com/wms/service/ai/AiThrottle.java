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
