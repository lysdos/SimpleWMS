package com.wms.filter;

import com.wms.dao.UserDao;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每请求回查的钉子(2026-09-28)。
 *
 * 本过滤器一直有个漏洞:它每次请求都会回查数据库,但只取 enabled / must_change_pw ——
 * role 仍用 token 里那份签发时的快照。于是把某人从 ADMIN 降为 OPERATOR 后,
 * 他会**带着管理员权限一直用到 token 过期**(默认 24h);而停用账号是立即生效的,
 * 同类机制里唯独 role 漏了。
 *
 * 这里钉住:库里的当前角色必须覆盖请求属性里的旧值;原有两道闸门不受影响。
 */
class UserStatusFilterTest {

    private final UserDao userDao = mock(UserDao.class);
    private final UserStatusFilter filter = new UserStatusFilter(userDao);

    private void givenStatus(boolean enabled, boolean mustChangePw, String role) {
        when(userDao.findStatusById(any(Integer.class)))
                .thenReturn(Optional.of(new UserDao.UserStatus(7, enabled, mustChangePw, role)));
    }

    /** 模拟一次请求:token 身份(userId / 旧 role)已由 JwtAuthFilter 放进属性 */
    private MockHttpServletResponse run(String tokenRole, boolean expectReachDown) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/products");
        req.setAttribute("userId", 7);
        if (tokenRole != null) req.setAttribute("role", tokenRole);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        if (expectReachDown) verify(chain, times(1)).doFilter(req, res);
        else verify(chain, never()).doFilter(any(), any());
        this.req = req;
        return res;
    }

    private MockHttpServletRequest req;

    @Test
    void databaseRole_overwritesStaleRoleFromToken() throws Exception {
        givenStatus(true, false, "OPERATOR");
        MockHttpServletResponse res = run("ADMIN", true);   // token 说 ADMIN,库里已是 OPERATOR
        assertEquals(200, res.getStatus());
        assertEquals("OPERATOR", req.getAttribute("role"));
    }

    @Test
    void promotedRole_isPickedUpImmediately() throws Exception {
        givenStatus(true, false, "ADMIN");
        run("OPERATOR", true);                              // token 说 OPERATOR,库里已升 ADMIN
        assertEquals("ADMIN", req.getAttribute("role"));
    }

    // ==================== 原有两道闸门不受影响 ====================

    @Test
    void disabledAccount_still401() throws Exception {
        givenStatus(false, false, "ADMIN");
        assertEquals(401, run("ADMIN", false).getStatus());
    }

    @Test
    void mustChangePw_still403() throws Exception {
        givenStatus(true, true, "ADMIN");
        assertEquals(403, run("ADMIN", false).getStatus());
    }

    @Test
    void unknownAccount_401() throws Exception {
        when(userDao.findStatusById(eq(7))).thenReturn(Optional.empty());
        assertEquals(401, run("ADMIN", false).getStatus());
    }
}
