package com.wms.web;

import com.wms.annotation.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

@Component
public class RoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod hm)) return true;
        // 先查方法注解,再回落到类注解
        RequireRole rr = hm.getMethodAnnotation(RequireRole.class);
        if (rr == null) rr = hm.getBeanType().getAnnotation(RequireRole.class);
        if (rr == null) return true;

        String role = (String) req.getAttribute("role");
        if (role == null) {
            res.setStatus(401);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"code\":401,\"message\":\"未登录\"}");
            return false;
        }
        if (!Arrays.asList(rr.value()).contains(role)) {
            res.setStatus(403);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"code\":403,\"message\":\"权限不足\"}");
            return false;
        }
        return true;
    }
}