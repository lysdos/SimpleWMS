package com.wms.filter;

import com.wms.dao.UserDao;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 必须改密/已禁用/已删除账号的拦截器。在 JwtAuthFilter 之后运行,
 * 保证 token 已经被验过签名并把 userId 放进了 request attribute。
 *
 *   - 已禁用 / 已删除 → 401(等效 token 立即失效,不必等 TTL)
 *   - mustChangePw=true → 除 POST /api/auth/me/change-password 外全 403
 *
 * 不加 @Component:由 WebConfig.userStatusFilterRegistration 显式注册,
 * 这样 JwtAuthFilter (auto-registered, @Component) 先跑、UserStatusFilter 后跑,
 * 顺序固定;@Component 还会让 Spring 额外自动注册一次,容易出现链顺序不确定。
 */
public class UserStatusFilter extends OncePerRequestFilter {

    private static final Set<String> CHANGE_PW_ALLOWED = Set.of(
        "/api/auth/me/change-password"
    );

    private final UserDao userDao;

    @Autowired
    public UserStatusFilter(UserDao userDao) { this.userDao = userDao; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Object uidObj = req.getAttribute("userId");
        if (uidObj == null) { chain.doFilter(req, res); return; }
        int uid = (int) uidObj;
        String path = req.getRequestURI();

        var st = userDao.findStatusById(uid).orElse(null);
        if (st == null) {
            writeErr(res, 401, "账号不存在");
            return;
        }
        if (!st.enabled()) {
            writeErr(res, 401, "账号已禁用");
            return;
        }
        if (st.mustChangePw() && !CHANGE_PW_ALLOWED.contains(path)) {
            writeErr(res, 403, "首次登录必须修改密码");
            return;
        }
        chain.doFilter(req, res);
    }

    private void writeErr(HttpServletResponse res, int code, String msg) throws IOException {
        res.setStatus(code);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":" + code + ",\"message\":\"" + msg + "\"}");
    }
}
