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
import java.util.regex.Pattern;

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
     * 唯一免登录的 /api 资源:条码图。
     *
     * 为什么只留它:前端用 `<img src="/api/inventory/items/{id}/barcode.png">` 渲染
     * (app.js 的条码弹窗与入库单详情),`<img>` 无法携带 Authorization 头,只能放开。
     *
     * 为什么改成正则精确匹配:2026-09-28 之前这里按「公开前缀 + `/export.csv` 或
     * `/barcode.png` 后缀」判断,而 `/api/inventory/items/` 也在前缀列表里 ——
     * 于是 `GET /api/inventory/items/export.csv` 命中,任何人无需 token 即可下载
     * 全量库存台账(实测 200 + 数千行 CSV,含 24 位码/SKU/创建人/单据 id)。
     * 单据导出同理可被枚举 id 匿名读取。
     *
     * 现在:export.csv 一律要求登录(前端三个导出入口已改走 api.download,会带 Bearer);
     * 后缀匹配彻底删除 —— 将来新增任何 `/export.csv` 路由都不会被静默放行。
     */
    private static final Pattern PUBLIC_READONLY =
            Pattern.compile("^/api/inventory/items/\\d+/barcode\\.png$");

    /** 仅当 GET 请求且路径命中上面那条精确规则时,才免登录 */
    private boolean isPublicReadonly(String method, String path) {
        return "GET".equalsIgnoreCase(method) && PUBLIC_READONLY.matcher(path).matches();
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