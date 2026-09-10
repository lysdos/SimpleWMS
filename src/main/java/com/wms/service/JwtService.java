package com.wms.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class JwtService {

    /** 绝对禁止作为 jwt.secret 的默认值(生产公开可见的源字符串会泄露签名密钥) */
    private static final String DEFAULT_SECRET = "please-change-me-in-prod-32-bytes!";
    private static final String DEFAULT_SECRET_PLACEHOLDER = "${jwt.secret}";

    private final SecretKey key;
    private final long ttlMillis;

    public JwtService(@Value("${jwt.secret:}") String secret,
                      @Value("${jwt.ttl-hours:24}") long ttlHours) {
        // 与 docker-compose 保持一致:env 未注入必须 fail-fast(避免退化为源码常量)
        if (secret == null || secret.isBlank() || DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                "jwt.secret 未配置(或为已知默认常量);请在 .env 中生成 " +
                "openssl rand -base64 48 并设置 JWT_SECRET,再启动服务。");
        }
        if (secret.startsWith(DEFAULT_SECRET_PLACEHOLDER)) {
            throw new IllegalStateException("jwt.secret 取到了字面 placeholder,启动失败");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("jwt.secret 至少 32 字节,当前 " + bytes.length);
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlMillis = ttlHours * 60L * 60L * 1000L;
    }

    public String issue(int userId, String username, String role) {
        Instant now = Instant.now();
        Map<String, Object> claims = new HashMap<>();
        claims.put("uname", username);
        claims.put("role", role);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(ttlMillis)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}