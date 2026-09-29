package com.wms.controller;

import com.wms.model.ApiResponse;
import com.wms.model.AppUser;
import com.wms.service.JwtService;
import com.wms.service.LoginThrottle;
import com.wms.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 内部受信调用方声明"豁免登录限流"用的头,值 = 共享密钥。 */
    private static final String NO_THROTTLE_HEADER = "X-WMS-No-Throttle";

    private final UserService userService;
    private final JwtService jwt;
    private final LoginThrottle throttle;

    /** 受信调用方用的共享密钥(.env 的 WMS_LOGIN_NO_THROTTLE_TOKEN);空白 = 豁免关闭。 */
    @Value("${wms.login.no-throttle-token:}")
    private String noThrottleToken;

    @Autowired
    public AuthController(UserService userService, JwtService jwt, LoginThrottle throttle) {
        this.userService = userService;
        this.jwt = jwt;
        this.throttle = throttle;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body, HttpServletRequest req) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(400, "缺少 username 或 password"));
        }
        String clientIp = clientIp(req);
        // 受信内部调用方(UHF-Rader 容器)豁免限流:既不看锁定、也不计失败 ——
        // 只豁免一半的话,终端页面的误输仍会把这个桶记满,回头把它的自动登录顶掉。
        boolean throttled = !throttleExempt(req);
        // 已被限流就别再走一遍 BCrypt —— 那个计算开销正是暴力破解想要的。
        // TooManyRequestsException 直接往上抛,由 GlobalExceptionHandler 映射成 429,
        // 不在这里 catch(否则会和下面两个 catch 的语义混在一起)。
        if (throttled) throttle.check(username, clientIp);
        try {
            AppUser u = userService.login(username, password);
            if (throttled) throttle.onSuccess(username, clientIp);
            String token = jwt.issue(u.getId(), u.getUsername(), u.getRole());
            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("username", u.getUsername());
            data.put("role", u.getRole());
            data.put("mustChangePw", Boolean.TRUE.equals(u.getMustChangePw()));
            return ResponseEntity.ok(ApiResponse.ok(data));
        } catch (IllegalStateException e) {
            // 账号已禁用:不是"猜错密码",不计数(否则停用账号期间反而连带触发限流)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.fail(403, e.getMessage()));
        } catch (IllegalArgumentException e) {
            if (throttled) throttle.onFailure(username, clientIp);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail(401, e.getMessage()));
        }
    }

    /**
     * 是否豁免登录失败限流。
     *
     * UHF-Rader 终端的人机登录(它的登录页)与后台自动登录都是**同一个容器**代发的:
     * 后端分不清两者,也无从按来源 IP 区分(docker 网络里容器 IP 重建后会漂)。
     * 而该终端被锁 5 分钟 = 现场整条出库扫码线停摆,所以按容器整体豁免。
     *
     * 密钥只在容器进程内,不下发浏览器;未配置(空白)时豁免整体关闭 —— fail-closed,
     * 不会出现"配漏了反而放行"。比较走常量时间,不给出逐字节试探的余地。
     * WMS 网页登录页与 PDA 不带这个头,照旧受限流保护。
     */
    private boolean throttleExempt(HttpServletRequest req) {
        if (noThrottleToken == null || noThrottleToken.isBlank()) return false;
        String got = req.getHeader(NO_THROTTLE_HEADER);
        if (got == null) return false;
        return MessageDigest.isEqual(
                got.trim().getBytes(StandardCharsets.UTF_8),
                noThrottleToken.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 取客户端真实 IP(限流按 IP 计数用)。
     *
     * 优先 `X-Real-IP`:nginx(`docker/nginx.conf`)用 `proxy_set_header X-Real-IP $remote_addr`
     * **覆写**了这个头,客户端自己伪造的值会被换掉,所以可信(不像 X-Forwarded-For 是追加的,
     * 追加就意味着"客户端说什么就是什么")。裸跑不经 nginx 时没这个头,退回 socket 地址 ——
     * 此时后端只绑 127.0.0.1,也只有本机能伪造。
     */
    private static String clientIp(HttpServletRequest req) {
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        return req.getRemoteAddr();
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        // JWT 无状态;前端清 token。后端仅 200。
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest req) {
        Object uid = req.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiResponse.fail(401, "未登录"));
        Map<String, Object> data = new HashMap<>();
        data.put("userId", req.getAttribute("userId"));
        data.put("username", req.getAttribute("username"));
        data.put("role", req.getAttribute("role"));
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    @PostMapping("/me/change-password")
    public ResponseEntity<?> changeOwn(HttpServletRequest req, @RequestBody Map<String, String> body) {
        Object uid = req.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiResponse.fail(401, "未登录"));
        try {
            userService.changeOwnPassword((Integer) uid, body.get("oldPassword"), body.get("newPassword"));
            return ResponseEntity.ok(ApiResponse.ok());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(400, e.getMessage()));
        }
    }
}