package com.codearena.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtVerifier {

    private final SecretKey key;
    private final String issuer;

    public JwtVerifier(
            @Value("${codearena.auth.jwt-secret:codearena-dev-jwt-secret-change-me-32b}") String secret,
            @Value("${codearena.auth.jwt-issuer:codearena}") String issuer) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, Math.min(bytes.length, 32));
            for (int i = bytes.length; i < 32; i++) {
                padded[i] = (byte) i;
            }
            bytes = padded;
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.issuer = issuer;
    }

    public Claims verify(String bearerOrJwt) {
        String token = bearerOrJwt == null ? "" : bearerOrJwt.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        }
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
