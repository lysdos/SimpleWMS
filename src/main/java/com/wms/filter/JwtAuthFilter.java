package com.wms.filter;

import com.wms.service.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 完全公开的路径(无需 token):登录 */
    private static final Set<String> PUBLIC = Set.of(
        "/api/auth/login"
    );

    private final JwtService jwt;

    @Autowired
    public JwtAuthFilter(JwtService jwt) { this.jwt = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        String header = req.getHeader("Authorization");

        // 1. 尝试解析 Bearer token
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims c = jwt.parse(header.substring(7));
                req.setAttribute("userId", Integer.parseInt(c.getSubject()));
                req.setAttribute("username", c.get("uname", String.class));
                req.setAttribute("role", c.get("role", String.class));
            } catch (Exception e) {
                if (!PUBLIC.contains(path)) {
                    writeErr(res, 401, "token 无效或已过期");
                    return;
                }
            }
        }

        // 2. /api/* 路径鉴权
        if (path.startsWith("/api/")) {
            // 公开路径放过
            if (PUBLIC.contains(path)) {
                chain.doFilter(req, res);
                return;
            }
            // 其他路径必须已鉴权
            if (req.getAttribute("userId") == null) {
                writeErr(res, 401, "未登录");
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