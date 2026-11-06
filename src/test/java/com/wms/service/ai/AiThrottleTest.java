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
