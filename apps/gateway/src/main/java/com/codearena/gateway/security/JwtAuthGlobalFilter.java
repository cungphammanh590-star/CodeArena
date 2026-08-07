package com.codearena.gateway.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway 统一 JWT 鉴权：公开路径放行；其余要求 Bearer JWT，并注入可信用户头给下游。
 */
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    public static final String HDR_PUBLIC_ID = "X-User-Public-Id";
    public static final String HDR_USER_ID = "X-User-Id";
    public static final String HDR_USERNAME = "X-Username";
    public static final String HDR_GATEWAY_AUTH = "X-CodeArena-Gateway-Auth";

    private static final Set<String> PUBLIC_EXACT = Set.of(
            "/health",
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/device");

    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/fallback/",
            "/actuator/");

    private final JwtVerifier jwtVerifier;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtAuthGlobalFilter(JwtVerifier jwtVerifier) {
        this.jwtVerifier = jwtVerifier;
    }

    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (request.getMethod() == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }

        String path = request.getURI().getPath();
        if (isPublic(path)) {
            // 防伪造：公开接口也去掉客户端自带的用户头
            ServerHttpRequest cleaned = request.mutate()
                    .headers(h -> {
                        h.remove(HDR_PUBLIC_ID);
                        h.remove(HDR_USER_ID);
                        h.remove(HDR_USERNAME);
                        h.remove(HDR_GATEWAY_AUTH);
                    })
                    .build();
            return chain.filter(exchange.mutate().request(cleaned).build());
        }

        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            return unauthorized(exchange, "请先登录");
        }

        try {
            Claims claims = jwtVerifier.verify(authorization);
            String publicId = claims.getSubject();
            Object uid = claims.get("uid");
            String username = stringClaim(claims, "username");

            ServerHttpRequest mutated = request.mutate()
                    .headers(h -> {
                        h.remove(HDR_PUBLIC_ID);
                        h.remove(HDR_USER_ID);
                        h.remove(HDR_USERNAME);
                        h.set(HDR_GATEWAY_AUTH, "jwt");
                        if (publicId != null && !publicId.isBlank()) {
                            h.set(HDR_PUBLIC_ID, publicId);
                        }
                        if (uid != null) {
                            h.set(HDR_USER_ID, String.valueOf(uid));
                        }
                        if (username != null && !username.isBlank()) {
                            h.set(HDR_USERNAME, username);
                        }
                    })
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (JwtException | IllegalArgumentException e) {
            return unauthorized(exchange, "登录已失效，请重新登录");
        }
    }

    private static boolean isPublic(String path) {
        if (path == null) {
            return false;
        }
        if (PUBLIC_EXACT.contains(path)) {
            return true;
        }
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String stringClaim(Claims claims, String name) {
        Object v = claims.get(name);
        return v == null ? null : String.valueOf(v);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 401);
        body.put("error", "Unauthorized");
        body.put("message", message);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            bytes = ("{\"status\":401,\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
