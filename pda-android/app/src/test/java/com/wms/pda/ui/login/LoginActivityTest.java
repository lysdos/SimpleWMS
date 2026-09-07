package com.wms.pda.ui.login;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LoginActivityTest {

    private static final String DEFAULT = "http://10.0.2.2:8080";

    @Test
    public void empty_returnsDefault() {
        assertEquals(DEFAULT, LoginActivity.normalizeBaseUrl("", DEFAULT));
        assertEquals(DEFAULT, LoginActivity.normalizeBaseUrl("   ", DEFAULT));
    }

    @Test
    public void bareIp_getsHttpPrefix() {
        assertEquals("http://192.168.1.100:8080",
                LoginActivity.normalizeBaseUrl("192.168.1.100:8080", DEFAULT));
    }

    @Test
    public void ipWithoutPort_preserved() {
        assertEquals("http://192.168.1.100",
                LoginActivity.normalizeBaseUrl("192.168.1.100", DEFAULT));
    }

    @Test
    public void fullUrl_preserved() {
        assertEquals("https://wms.example.com",
                LoginActivity.normalizeBaseUrl("https://wms.example.com", DEFAULT));
    }

    @Test
    public void trailingSlash_trimmed() {
        assertEquals("http://192.168.1.100:8080",
                LoginActivity.normalizeBaseUrl("http://192.168.1.100:8080/", DEFAULT));
        assertEquals("http://192.168.1.100:8080",
                LoginActivity.normalizeBaseUrl("http://192.168.1.100:8080///", DEFAULT));
    }

    @Test
    public void surroundingWhitespace_trimmed() {
        assertEquals("http://192.168.1.100:8080",
                LoginActivity.normalizeBaseUrl("  192.168.1.100:8080  ", DEFAULT));
    }
}
