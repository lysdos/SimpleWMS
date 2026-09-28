package com.wms.service;

import com.wms.controller.TooManyRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 登录失败限流的阈值与两把尺子(2026-09-28)。
 *
 * 关键是别把无辜的人锁在外面:
 *  - 同一 (用户名, IP) 连错 5 次 → 这一对锁 5 分钟(挡"盯着一个账号猛试");
 *  - 同一 IP 累计错 20 次 → 这个 IP 锁 5 分钟(挡"换着用户名扫号");
 *  - 别的 IP 不受影响 —— 否则一个人就能拿别人的工号把别人的账号锁掉;
 *  - 登录成功立即清零。
 */
class LoginThrottleTest {

    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        throttle = new LoginThrottle();
    }

    private void fail(String username, String ip) {
        throttle.onFailure(username, ip);
    }

    @Test
    void fourFailuresAreTolerated_fifthLocks() {
        for (int i = 0; i < 4; i++) {
            fail("admin", "10.0.0.1");
            assertDoesNotThrow(() -> throttle.check("admin", "10.0.0.1"));
        }
        fail("admin", "10.0.0.1");   // 第 5 次
        assertThrows(TooManyRequestsException.class, () -> throttle.check("admin", "10.0.0.1"));
    }

    @Test
    void lockIsPerPair_notGlobalForThatUsername() {
        for (int i = 0; i < 5; i++) fail("admin", "10.0.0.1");
        assertThrows(TooManyRequestsException.class, () -> throttle.check("admin", "10.0.0.1"));
        // 同一个账号换台机器照样能登 —— 攻击者锁不掉别人的账号
        assertDoesNotThrow(() -> throttle.check("admin", "10.0.0.2"));
    }

    @Test
    void anotherUserFromSameIpIsNotYetAffected() {
        for (int i = 0; i < 5; i++) fail("admin", "10.0.0.1");
        // IP 侧才 5 次,离 20 还远:同事在同一出口下正常登录不该被连坐
        assertDoesNotThrow(() -> throttle.check("zhangsan", "10.0.0.1"));
    }

    @Test
    void ipWideLimitStopsUsernameScanning() {
        for (int i = 0; i < 20; i++) fail("user" + i, "10.0.0.9");
        assertThrows(TooManyRequestsException.class, () -> throttle.check("brand-new-user", "10.0.0.9"));
        // 别的 IP 不受影响
        assertDoesNotThrow(() -> throttle.check("brand-new-user", "10.0.0.10"));
    }

    @Test
    void successClearsBothCounters() {
        for (int i = 0; i < 4; i++) fail("admin", "10.0.0.1");
        throttle.onSuccess("admin", "10.0.0.1");
        // 清零后再错 4 次仍不该锁(否则"偶尔手滑"会累积成锁定)
        for (int i = 0; i < 4; i++) {
            fail("admin", "10.0.0.1");
            assertDoesNotThrow(() -> throttle.check("admin", "10.0.0.1"));
        }
    }

    @Test
    void nullIpIsHandledAsOneBucket() {
        for (int i = 0; i < 5; i++) fail("admin", null);
        assertThrows(TooManyRequestsException.class, () -> throttle.check("admin", null));
    }
}
