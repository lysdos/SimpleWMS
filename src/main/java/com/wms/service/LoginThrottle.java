package com.wms.service;

import com.wms.controller.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流(进程内计数,单实例够用)。
 *
 * 为什么要两把尺子:只按用户名计数,任何人在登录页连输 5 次错密码就能把别人的账号锁 5 分钟
 * (拿别人的工号当靶子太容易);只按 IP 计数,整个办公室共用 nginx 一个出口时又会互相连坐。
 * 所以:
 *   - (用户名 + IP) 这一对:5 次失败锁 5 分钟 —— 挡的是"盯着一个账号猛试";
 *   - 单独按 IP:20 次失败锁 5 分钟 —— 挡的是"换着用户名扫口令"(扫号通常来自同一台机器)。
 * 单 IP 的攻击者只能锁住自己这一对,同一账号从别的机器(受害者自己)照常能登。
 *
 * 计数窗口即锁定时长(5 分钟滑动);登录成功立即清零。表按 key 惰性清理(见 purgeIfCrowded),
 * 不需要调度线程。JWT 有效期 24h 与此无关 —— 这里只管登录入口。
 */
@Component
public class LoginThrottle {

    /** 同一 (用户名, IP) 的失败上限 */
    private static final int MAX_PER_PAIR = 5;
    /** 同一 IP 的失败上限(防换用户名扫号) */
    private static final int MAX_PER_IP = 20;
    /** 锁定时长 = 计数窗口 */
    private static final Duration LOCK = Duration.ofMinutes(5);
    /** 表大小超过它就顺手清一遍过期条目,避免被海量随机用户名撑大 */
    private static final int PURGE_THRESHOLD = 1000;

    private static final class Attempt {
        int failures;
        Instant lockedUntil;
        Instant lastTouch;
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** 登录前调用:命中锁定则直接拒绝(不会再走去验密码) */
    public void check(String username, String clientIp) {
        Instant now = Instant.now();
        for (String key : keys(username, clientIp)) {
            Attempt a = attempts.get(key);
            if (a == null) continue;
            synchronized (a) {
                if (a.lockedUntil != null && now.isBefore(a.lockedUntil)) {
                    throw new TooManyRequestsException(
                            "登录失败次数过多,请 " + remainingMinutes(now, a.lockedUntil) + " 分钟后再试");
                }
            }
        }
    }

    /** 每次认证失败调用 */
    public void onFailure(String username, String clientIp) {
        Instant now = Instant.now();
        record(pairKey(username, clientIp), MAX_PER_PAIR, now);
        record(ipKey(clientIp), MAX_PER_IP, now);
        purgeIfCrowded(now);
    }

    /** 登录成功后调用:清掉这一对与这个 IP 的计数 */
    public void onSuccess(String username, String clientIp) {
        attempts.remove(pairKey(username, clientIp));
        attempts.remove(ipKey(clientIp));
    }

    private void record(String key, int max, Instant now) {
        attempts.compute(key, (k, prev) -> {
            Attempt a = prev;
            // 锁定早已过期(或被遗忘)的旧条目,重新开始计数
            if (a == null || (a.lockedUntil != null && now.isAfter(a.lockedUntil))) {
                a = new Attempt();
            }
            a.failures++;
            a.lastTouch = now;
            if (a.failures >= max) {
                a.lockedUntil = now.plus(LOCK);
                a.failures = 0;   // 归零,锁定期满后重新计数
            }
            return a;
        });
    }

    private void purgeIfCrowded(Instant now) {
        if (attempts.size() <= PURGE_THRESHOLD) return;
        Instant cutoff = now.minus(LOCK.multipliedBy(2));
        attempts.entrySet().removeIf(e -> {
            Attempt a = e.getValue();
            synchronized (a) {
                return a.lastTouch != null && a.lastTouch.isBefore(cutoff)
                        && (a.lockedUntil == null || now.isAfter(a.lockedUntil));
            }
        });
    }

    private static long remainingMinutes(Instant now, Instant until) {
        long secs = Duration.between(now, until).toSeconds();
        return Math.max(1, (secs + 59) / 60);
    }

    private static String[] keys(String username, String clientIp) {
        return new String[]{ pairKey(username, clientIp), ipKey(clientIp) };
    }

    private static String pairKey(String username, String clientIp) {
        return "u:" + (username == null ? "" : username.toLowerCase()) + "|ip:" + ipKey(clientIp);
    }

    private static String ipKey(String clientIp) {
        return clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
    }
}
