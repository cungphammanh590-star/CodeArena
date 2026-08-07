package com.codearena.business.user.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HS256 JWT：Gateway 与 business 共用同一密钥校验。
 * claims: sub=publicId, uid, username, display_name, jti, client
 */
@Component
public class JwtTokenService {

    public static final String CLAIM_UID = "uid";
    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_DISPLAY_NAME = "display_name";
    public static final String CLAIM_CLIENT = "client";

    private final SecretKey key;
    private final String issuer;
    private final int ttlDays;

    public JwtTokenService(
            @Value("${codearena.auth.jwt-secret:codearena-dev-jwt-secret-change-me-32b}") String secret,
            @Value("${codearena.auth.jwt-issuer:codearena}") String issuer,
            @Value("${codearena.auth.token-ttl-days:30}") int ttlDays) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // HS256 要求足够长的密钥
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, Math.min(bytes.length, 32));
            for (int i = bytes.length; i < 32; i++) {
                padded[i] = (byte) i;
            }
            bytes = padded;
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.issuer = issuer;
        this.ttlDays = Math.max(1, ttlDays);
    }

    public record IssuedJwt(String token, String jti, OffsetDateTime expiresAt) {}

    public IssuedJwt issue(
            String publicId,
            long userId,
            String username,
            String displayName,
            String client) {
        String jti = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlDays * 24L * 3600L);
        String token = Jwts.builder()
                .issuer(issuer)
                .subject(publicId)
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim(CLAIM_UID, userId)
                .claim(CLAIM_USERNAME, username == null ? "" : username)
                .claim(CLAIM_DISPLAY_NAME, displayName == null ? "" : displayName)
                .claim(CLAIM_CLIENT, client == null ? "web" : client)
                .signWith(key)
                .compact();
        return new IssuedJwt(token, jti, OffsetDateTime.ofInstant(exp, ZoneOffset.UTC));
    }

    public Claims parse(String rawToken) {
        String token = stripBearer(rawToken);
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public static String stripBearer(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim();
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return t.substring(7).trim();
        }
        return t;
    }

    public static boolean looksLikeJwt(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String t = stripBearer(token);
        int a = t.indexOf('.');
        int b = t.lastIndexOf('.');
        return a > 0 && b > a;
    }
}
