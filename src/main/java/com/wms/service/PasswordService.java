package com.wms.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordService {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    public String hash(String raw) {
        return encoder.encode(raw);
    }

    public boolean verify(String raw, String hashed) {
        if (raw == null || hashed == null) return false;
        return encoder.matches(raw, hashed);
    }
}