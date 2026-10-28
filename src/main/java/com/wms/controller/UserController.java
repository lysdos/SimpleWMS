package com.wms.controller;

import com.wms.annotation.RequireRole;
import com.wms.model.ApiResponse;
import com.wms.model.AppUser;
import com.wms.service.UserService;
import com.wms.util.CsvWriter;
import com.wms.util.Dates;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
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
    public ApiResponse<List<AppUser>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String beginDate,
            @RequestParam(required = false) String endDate) {
        return ApiResponse.ok(userService.findUsers(keyword, role, status, beginDate, endDate));
    }

    @GetMapping("/{id}")
    public ApiResponse<AppUser> get(@PathVariable int id) {
        return ApiResponse.ok(userService.get(id));
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

    @PutMapping("/{id}")
    public ApiResponse<AppUser> update(@PathVariable int id, @RequestBody Map<String, String> body) {
        String role = body.get("role");
        AppUser u = userService.updateRole(id, role);
        u.setPasswordHash(null);
        return ApiResponse.ok(u);
    }

    /**
     * 删除用户 — 不级联删业务记录(inventory_log.created_by /
     * inbound_order.created_by / outbound_order.created_by 是 VARCHAR,
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

    // ==================== 导出(不做导入) ====================

    /**
     * 用户导出**只读**。
     *
     * 为什么不做导入:UserService.create 要求**明文密码**,导入就等于把明文密码
     * 写进 CSV —— 文件会留在下载目录、被转发、进邮件。而用户数量本就少(通常个位数
     * 到几十个),手工建不痛。风险与收益完全不成比例。
     *
     * 为什么列白名单而不是直接序列化 AppUser:
     * passwordHash 上有 @JsonIgnore 挡 JSON,但 CSV 走的是 getter —— 不显式排除
     * 就会把 BCrypt 哈希写进明文件。
     */
    private static final String[] EXPORT_HEADERS = {"ID", "用户名", "角色", "状态", "需改密", "创建人", "创建时间"};

    /** 导出(按筛选)。筛选参数与列表接口一致,不传即全量。 */
    @GetMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String beginDate,
            @RequestParam(required = false) String endDate) {
        return CsvWriter.download("users.csv", EXPORT_HEADERS,
                toRows(userService.findUsers(keyword, role, status, beginDate, endDate)));
    }

    /** 导出选中项(POST + id 数组) */
    @PostMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportSelected(@RequestBody List<Integer> ids) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("请选择要导出的用户");
        return CsvWriter.download("users.csv", EXPORT_HEADERS, toRows(userService.listByIds(ids)));
    }

    private static List<Object[]> toRows(List<AppUser> list) {
        List<Object[]> rows = new java.util.ArrayList<>();
        for (AppUser u : list) {
            rows.add(new Object[]{
                    u.getId(), u.getUsername(), u.getRole(),
                    Boolean.FALSE.equals(u.getEnabled()) ? "禁用" : "启用",
                    Boolean.TRUE.equals(u.getMustChangePw()) ? "是" : "否",
                    u.getCreatedBy(), Dates.fmt(u.getCreatedAt())
            });
        }
        return rows;
    }
}