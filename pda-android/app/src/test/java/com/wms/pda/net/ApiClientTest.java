package com.wms.pda.net;

import com.wms.pda.data.SessionStore;
import com.wms.pda.model.ApiResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ApiClientTest {

    private MockWebServer server;
    private SessionStore store;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        store = new SessionStore(org.robolectric.RuntimeEnvironment.application);
        // 覆盖 baseUrl 为 mock server
        store.setBaseUrl(server.url("").toString());
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    public void get_success() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "{\"code\":0,\"msg\":\"\",\"data\":{\"id\":1,\"sku\":\"SP-001\"}}"));

        ApiClient client = new ApiClient(store);
        ApiResponse<TestProduct> resp = client.get("/api/products/1", TestProduct.class);
        assertNotNull(resp);
        assertEquals(0, resp.code);
        assertNotNull(resp.data);
        assertEquals(1, resp.data.id);
        assertEquals("SP-001", resp.data.sku);

        RecordedRequest req = server.takeRequest();
        assertEquals("GET", req.getMethod());
        assertEquals("/api/products/1", req.getPath());
    }

    @Test
    public void get_authHeaderInjected() throws Exception {
        store.setToken("jwt-abc");
        server.enqueue(new MockResponse().setBody("{\"code\":0,\"msg\":\"\",\"data\":{}}"));

        ApiClient client = new ApiClient(store);
        client.get("/api/auth/me", Object.class);

        RecordedRequest req = server.takeRequest();
        assertEquals("Bearer jwt-abc", req.getHeader("Authorization"));
    }

    @Test
    public void get_businessError() {
        server.enqueue(new MockResponse().setBody(
                "{\"code\":404,\"msg\":\"未找到\",\"data\":null}"));

        ApiClient client = new ApiClient(store);
        try {
            client.get("/api/missing", Object.class);
            fail("应抛 ApiException");
        } catch (ApiException e) {
            assertEquals(404, e.getCode());
            assertTrue(e.getMessage().contains("未找到"));
        }
    }

    @Test
    public void get_authError401_clearsSession() {
        store.setToken("old-jwt");
        server.enqueue(new MockResponse().setBody(
                "{\"code\":401,\"msg\":\"token 过期\",\"data\":null}"));

        ApiClient client = new ApiClient(store);
        try {
            client.get("/api/auth/me", Object.class);
            fail("应抛 ApiException");
        } catch (ApiException e) {
            assertEquals(ApiException.ERR_AUTH, e.getCode());
        }
        // token 被清
        assertEquals(null, store.getToken());
    }

    public static class TestProduct {
        public int id;
        public String sku;
    }
}
