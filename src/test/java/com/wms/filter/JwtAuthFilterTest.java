package com.wms.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 公开路径白名单的钉子(2026-09-28)。
 *
 * 这里钉住的是一次真实泄露:白名单原先按「公开前缀 + `/export.csv` 或 `/barcode.png` 后缀」
 * 判断,而 `/api/inventory/items/` 在前缀列表里 —— 于是
 * `GET /api/inventory/items/export.csv` 被当成公开路径,任何人无需 token 即可下载
 * 全量库存台账(实测 HTTP 200 + 数千行 CSV)。单据导出同样可被枚举 id 匿名读取。
 *
 * 现在只放开条码图一条精确规则(<img> 带不了 Authorization 头),其余 export.csv 一律要登录。
 * 这些用例不连数据库、不起 Spring 上下文:过滤器在无 Bearer 头时根本不碰 JwtService。
 */
class JwtAuthFilterTest {

    private final JwtAuthFilter filter = new JwtAuthFilter();

    /** 无 token 发一个请求;返回响应,并断言过滤器链是否被放行 */
    private MockHttpServletResponse call(String method, String uri, boolean expectReachDown) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        if (expectReachDown) verify(chain, times(1)).doFilter(req, res);
        else verify(chain, never()).doFilter(any(), any());
        return res;
    }

    // ==================== 曾经漏掉的:导出接口必须登录 ====================

    @Test
    void inventoryExportCsv_requiresLogin() throws Exception {
        MockHttpServletResponse res = call("GET", "/api/inventory/items/export.csv", false);
        assertEquals(401, res.getStatus());
    }

    @Test
    void inboundOrderExportCsv_requiresLogin() throws Exception {
        MockHttpServletResponse res = call("GET", "/api/inbound/orders/1/export.csv", false);
        assertEquals(401, res.getStatus());
    }

    @Test
    void outboundOrderExportCsv_requiresLogin() throws Exception {
        MockHttpServletResponse res = call("GET", "/api/outbound/orders/1/export.csv", false);
        assertEquals(401, res.getStatus());
    }

    @Test
    void stockCheckExportCsv_requiresLogin() throws Exception {
        MockHttpServletResponse res = call("GET", "/api/stock-checks/1/export.csv", false);
        assertEquals(401, res.getStatus());
    }

    // ==================== 唯一保留的公开资源:条码图 ====================

    @Test
    void barcodePng_stillPublic() throws Exception {
        MockHttpServletResponse res = call("GET", "/api/inventory/items/3/barcode.png", true);
        assertEquals(200, res.getStatus());
    }

    /** 非数字 id 不匹配精确规则:别让 /api/inventory/items/export.csv 之外的写法绕进来 */
    @Test
    void barcodePng_withNonNumericId_isNotPublic() throws Exception {
        MockHttpServletResponse res = call("GET", "/api/inventory/items/abc/barcode.png", false);
        assertEquals(401, res.getStatus());
    }

    /** 只对 GET 放开:同样的路径用 POST 必须登录 */
    @Test
    void barcodePng_postIsNotPublic() throws Exception {
        MockHttpServletResponse res = call("POST", "/api/inventory/items/3/barcode.png", false);
        assertEquals(401, res.getStatus());
    }

    // ==================== 普通接口仍然要登录 ====================

    @Test
    void plainApi_requiresLogin() throws Exception {
        MockHttpServletResponse res = call("GET", "/api/products", false);
        assertEquals(401, res.getStatus());
    }
}
