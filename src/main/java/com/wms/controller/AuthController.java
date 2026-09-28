package com.wms.controller;

import com.wms.model.ApiResponse;
import com.wms.model.AppUser;
import com.wms.service.JwtService;
import com.wms.service.LoginThrottle;
import com.wms.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtService jwt;
    private final LoginThrottle throttle;

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
        // 已被限流就别再走一遍 BCrypt —— 那个计算开销正是暴力破解想要的。
        // TooManyRequestsException 直接往上抛,由 GlobalExceptionHandler 映射成 429,
        // 不在这里 catch(否则会和下面两个 catch 的语义混在一起)。
        throttle.check(username, clientIp);
        try {
            AppUser u = userService.login(username, password);
            throttle.onSuccess(username, clientIp);
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
            throttle.onFailure(username, clientIp);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail(401, e.getMessage()));
        }
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