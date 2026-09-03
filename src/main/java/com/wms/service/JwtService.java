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

    private final SecretKey key;
    private final long ttlMillis;

    public JwtService(@Value("${jwt.secret:please-change-me-in-prod-32-bytes!}") String secret,
                      @Value("${jwt.ttl-hours:24}") long ttlHours) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // HS256 要求 ≥ 32 字节;补齐
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, Math.min(bytes.length, 32));
            bytes = padded;
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