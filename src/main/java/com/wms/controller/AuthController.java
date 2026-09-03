package com.wms.controller;

import com.wms.model.ApiResponse;
import com.wms.model.AppUser;
import com.wms.service.JwtService;
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

    @Autowired
    public AuthController(UserService userService, JwtService jwt) {
        this.userService = userService;
        this.jwt = jwt;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(400, "缺少 username 或 password"));
        }
        try {
            AppUser u = userService.login(username, password);
            String token = jwt.issue(u.getId(), u.getUsername(), u.getRole());
            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("username", u.getUsername());
            data.put("role", u.getRole());
            data.put("mustChangePw", Boolean.TRUE.equals(u.getMustChangePw()));
            return ResponseEntity.ok(ApiResponse.ok(data));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.fail(403, e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail(401, e.getMessage()));
        }
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