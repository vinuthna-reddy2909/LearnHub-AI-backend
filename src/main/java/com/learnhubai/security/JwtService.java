package com.learnhubai.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecretKey key;

    public JwtService(@Value("${learnhub.jwt-secret}") String secret) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must contain at least 32 bytes");
        }
        key = Keys.hmacShaKeyFor(secretBytes);
    }

    public String generate(String userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim("id", userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(30, ChronoUnit.DAYS)))
                .signWith(key)
                .compact();
    }

    public String getUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.get("id", String.class);
    }
}
