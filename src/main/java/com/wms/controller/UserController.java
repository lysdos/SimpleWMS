package com.wms.controller;

import com.wms.annotation.RequireRole;
import com.wms.model.ApiResponse;
import com.wms.model.AppUser;
import com.wms.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequireRole("ADMIN")
public class UserController {

    private final UserService userService;

    @Autowired
    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ApiResponse<List<AppUser>> list() {
        return ApiResponse.ok(userService.list());
    }

    @PostMapping
    public ApiResponse<AppUser> create(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String role = body.get("role");
        AppUser u = userService.create(username, password, role);
        // 不返回 password_hash
        u.setPasswordHash(null);
        return ApiResponse.ok(u);
    }

    @PostMapping("/{id}/password")
    public ApiResponse<Void> resetPassword(@PathVariable int id, @RequestBody Map<String, String> body) {
        userService.resetPassword(id, body.get("password"));
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/enabled")
    public ApiResponse<Void> setEnabled(@PathVariable int id, @RequestBody Map<String, Boolean> body) {
        userService.setEnabled(id, Boolean.TRUE.equals(body.get("enabled")));
        return ApiResponse.ok();
    }

    /**
     * 删除用户 — 不级联删业务记录(inventory_log.operator /
     * inbound_order.operator / outbound_order.operator 是 VARCHAR,
     * 不是外键,天然不级联)。
     * 业务校验由 UserService.delete 负责:不能删自己/最后一名 ADMIN。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable int id, HttpServletRequest req) {
        Object uidObj = req.getAttribute("userId");
        int currentUserId = (uidObj instanceof Integer) ? (Integer) uidObj : -1;
        userService.delete(id, currentUserId);
        return ApiResponse.ok();
    }
}