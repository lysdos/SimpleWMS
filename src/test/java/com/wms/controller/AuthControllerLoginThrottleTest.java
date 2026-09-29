package com.wms.controller;

import com.wms.model.AppUser;
import com.wms.service.JwtService;
import com.wms.service.LoginThrottle;
import com.wms.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录限流对受信调用方的豁免(2026-09-29)。
 *
 * UHF-Rader 终端的人机登录(它的登录页)与出库扫码的自动登录是**同一个容器**代发的:
 * 后端分不清两者,也没法按来源 IP 区分(docker 网络里容器 IP 重建后会漂)。而该终端
 * 被限流锁 5 分钟 = 现场整条出库扫码线停摆,所以按容器整体豁免。
 *
 * 这里钉住四件事:
 *   1. 带对密钥头 → 不看锁定、也不计失败(只豁免一半的话,页面误输仍会把桶记满,
 *      回头把自动登录顶掉);
 *   2. 不带头 / 带错头 → 照旧限流,不受影响;
 *   3. 密钥没配(空白)→ 豁免整体关闭,拿头来也不认(fail-closed);
 *   4. 已锁定时照旧抛 TooManyRequestsException 给 GlobalExceptionHandler 变 429。
 */
class AuthControllerLoginThrottleTest {

    private static final String HEADER = "X-WMS-No-Throttle";
    private static final String TOKEN = "s3cret-token";

    private final UserService userService = mock(UserService.class);
    private final JwtService jwt = mock(JwtService.class);
    private final LoginThrottle throttle = mock(LoginThrottle.class);
    private final AuthController controller = new AuthController(userService, jwt, throttle);

    private void givenToken(String value) {
        ReflectionTestUtils.setField(controller, "noThrottleToken", value);
    }

    private MockHttpServletRequest req(String headerValue) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/auth/login");
        if (headerValue != null) r.addHeader(HEADER, headerValue);
        return r;
    }

    private void givenBadCredentials() {
        when(userService.login(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("用户名或密码错误"));
    }

    private ResponseEntity<?> login(String headerValue) {
        return controller.login(Map.of("username", "admin", "password", "whatever"),
                                req(headerValue));
    }

    // ==================== 受信调用方 ====================

    @Test
    void trustedCaller_onBadCredentials_doesNotCheckOrCount() {
        givenToken(TOKEN);
        givenBadCredentials();

        assertEquals(401, login(TOKEN).getStatusCode().value());
        verify(throttle, never()).check(any(), any());
        verify(throttle, never()).onFailure(any(), any());
    }

    @Test
    void trustedCaller_onSuccess_doesNotTouchCounters() {
        givenToken(TOKEN);
        AppUser u = new AppUser();
        u.setId(7);
        u.setUsername("admin");
        u.setRole("ADMIN");
        when(userService.login(anyString(), anyString())).thenReturn(u);
        when(jwt.issue(anyInt(), anyString(), anyString())).thenReturn("tok");

        assertEquals(200, login(TOKEN).getStatusCode().value());
        verify(throttle, never()).check(any(), any());
        verify(throttle, never()).onSuccess(any(), any());
    }

    @Test
    void headerValueIsTrimmed() {
        givenToken(TOKEN);
        givenBadCredentials();

        assertEquals(401, login("  " + TOKEN + "  ").getStatusCode().value());
        verify(throttle, never()).check(any(), any());
    }

    // ==================== 不受信的调用方照旧 ====================

    @Test
    void missingHeader_isStillThrottled() {
        givenToken(TOKEN);
        givenBadCredentials();

        assertEquals(401, login(null).getStatusCode().value());
        verify(throttle).check(anyString(), any());
        verify(throttle).onFailure(anyString(), any());
    }

    @Test
    void wrongToken_isStillThrottled() {
        givenToken(TOKEN);
        givenBadCredentials();

        assertEquals(401, login("not-the-token").getStatusCode().value());
        verify(throttle).check(anyString(), any());
        verify(throttle).onFailure(anyString(), any());
    }

    /** 密钥没配(或后端还没重建)时,豁免必须整体关闭 —— 不能"配漏了反而放行"。 */
    @Test
    void tokenNotConfigured_headerIsIgnored() {
        givenToken("");
        givenBadCredentials();

        assertEquals(401, login(TOKEN).getStatusCode().value());
        verify(throttle).check(anyString(), any());
        verify(throttle).onFailure(anyString(), any());
    }

    /** 已被锁定时:异常照旧上抛(GlobalExceptionHandler 映射成 429),且不再验密码。 */
    @Test
    void lockedOut_stillRaises429_andNeverVerifiesPassword() {
        givenToken(TOKEN);
        doThrow(new TooManyRequestsException("登录失败次数过多,请 5 分钟后再试"))
                .when(throttle).check(anyString(), any());

        try {
            login(null);
            throw new AssertionError("应当抛出 TooManyRequestsException");
        } catch (TooManyRequestsException expected) {
            // 预期路径
        }
        verify(userService, never()).login(anyString(), anyString());
    }
}
