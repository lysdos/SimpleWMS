package com.wms.filter;

import com.wms.service.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 由 WebConfig.jwtAuthFilterRegistration 显式登记(带 order=10),确保比
 * UserStatusFilter 先跑且整个鉴权链顺序确定。不加 @Component 防止 Spring
 * 额外自动注册造成双重过滤。
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 精确匹配的完全公开路径(无需 token) */
    private static final Set<String> PUBLIC = Set.of(
        "/api/auth/login"
    );

    /** 已鉴权用户也允许访问的路径(必须改密时不能锁死用户) */
    private static final Set<String> CHANGE_PW_ALLOWED = Set.of(
        "/api/auth/me/change-password"
    );

    /**
     * 公开访问的路径前缀(GET 请求无需 token):
     *   - /api/inbound/orders/{id}/export.csv   出入库单 CSV 导出(供邮件附件 / 外部系统集成)
     *   - /api/outbound/orders/{id}/export.csv
     *   - /api/stock-checks/{id}/export.csv     盘点单 CSV 导出
     *   - /api/inventory/items/{id}/barcode.png 条码图(供扫码枪 / 直接扫码读取)
     *
     * 注:这些只读查询接口,不修改数据,免登录可读。注意:
     *   1) 仅 GET 方法免登录,POST/PUT/DELETE 仍需鉴权
     *   2) 接口内部如发现非法 id(如负数 / 越界)请按业务规则返回 404/400
     */
    private static final String[] PUBLIC_PREFIXES = {
        "/api/inbound/orders/",
        "/api/outbound/orders/",
        "/api/stock-checks/",
        "/api/inventory/items/"
    };

    /** 仅当 GET 请求且路径命中公开前缀时,才返回 true */
    private boolean isPublicReadonly(String method, String path) {
        if (!"GET".equalsIgnoreCase(method)) return false;
        // 盘点单:整段 GET 接口(stock-check 详情接口也应免登录,与出入库对称)
        if (path.startsWith("/api/stock-checks/") && path.endsWith("/export.csv")) return true;
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix) && (path.endsWith("/export.csv") || path.endsWith("/barcode.png"))) {
                return true;
            }
        }
        return false;
    }

    private JwtService jwt;   // 由 WebConfig 注入(避免 @Component 双注册)

    public void setJwtService(JwtService jwt) { this.jwt = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        String method = req.getMethod();
        String header = req.getHeader("Authorization");

        // 1. 尝试解析 Bearer token(有 token 就解析,无效则按是否公开路径决定)
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims c = jwt.parse(header.substring(7));
                req.setAttribute("userId", Integer.parseInt(c.getSubject()));
                req.setAttribute("username", c.get("uname", String.class));
                req.setAttribute("role", c.get("role", String.class));
            } catch (Exception e) {
                if (!PUBLIC.contains(path) && !isPublicReadonly(method, path)) {
                    writeErr(res, 401, "token 无效或已过期");
                    return;
                }
            }
        }

        // 2. /api/* 路径鉴权
        if (path.startsWith("/api/")) {
            boolean isPublic = PUBLIC.contains(path) || isPublicReadonly(method, path);
            if (isPublic) {
                chain.doFilter(req, res);
                return;
            }
            // 其他路径必须已鉴权
            if (req.getAttribute("userId") == null) {
                writeErr(res, 401, "未登录");
                return;
            }
            // 暴露 mustChangePw / enabled 给后续过滤器;这里只跳过强制改密
            // 路径的白名单,真正的拦截/失活在 UserStatusFilter 统一处理。
            if (CHANGE_PW_ALLOWED.contains(path)) {
                chain.doFilter(req, res);
                return;
            }
        }

        chain.doFilter(req, res);
    }

    private void writeErr(HttpServletResponse res, int code, String msg) throws IOException {
        res.setStatus(code);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":" + code + ",\"message\":\"" + msg + "\"}");
    }
}