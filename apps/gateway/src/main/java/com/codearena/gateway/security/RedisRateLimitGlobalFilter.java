package com.codearena.gateway.security;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Redis shared fixed-window limiter; Redis outages fail open and are logged. */
@Component
public class RedisRateLimitGlobalFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitGlobalFilter.class);
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end; return n",
            Long.class);
    private final ReactiveStringRedisTemplate redis;
    @Value("${codearena.rate-limit.enabled:true}") private boolean enabled;
    @Value("${codearena.rate-limit.default-per-minute:60}") private int defaultLimit;
    @Value("${codearena.rate-limit.stream-per-minute:20}") private int streamLimit;
    @Value("${codearena.rate-limit.submit-per-minute:20}") private int submitLimit;
    @Value("${codearena.rate-limit.auth-per-minute:10}") private int authLimit;
    @Value("${codearena.rate-limit.export-per-minute:5}") private int exportLimit;

    public RedisRateLimitGlobalFilter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override public int getOrder() { return -50; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) return chain.filter(exchange);
        String path = exchange.getRequest().getURI().getPath();
        if (path == null || path.equals("/health") || path.startsWith("/actuator") || path.startsWith("/fallback")) {
            return chain.filter(exchange);
        }
        Bucket bucket = bucket(path);
        long minute = System.currentTimeMillis() / 60_000L;
        String key = "codearena:rl:" + bucket.name + ":" + clientKey(exchange.getRequest()) + ":" + minute;
        return redis.execute(SCRIPT, List.of(key), List.of("60")).next()
                .flatMap(count -> count != null && count > bucket.limit ? limited(exchange) : chain.filter(exchange))
                .switchIfEmpty(chain.filter(exchange))
                .onErrorResume(error -> {
                    log.warn("Redis limiter unavailable; fail open: {}", error.toString());
                    return chain.filter(exchange);
                });
    }

    private Bucket bucket(String path) {
        if (path.startsWith("/api/coach/stream")) return new Bucket("stream", streamLimit);
        if (path.equals("/submit")) return new Bucket("submit", submitLimit);
        if (path.startsWith("/api/auth/login") || path.startsWith("/api/auth/register")) return new Bucket("auth", authLimit);
        if (path.startsWith("/api/learning/export")) return new Bucket("export", exportLimit);
        return new Bucket("default", defaultLimit);
    }

    private Mono<Void> limited(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().set("Retry-After", "60");
        return exchange.getResponse().setComplete();
    }

    private static String clientKey(ServerHttpRequest request) {
        String user = request.getHeaders().getFirst("X-User-Public-Id");
        if (user != null && !user.isBlank()) return "u:" + user.trim();
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return "ip:" + forwarded.split(",")[0].trim();
        return request.getRemoteAddress() == null
                ? "ip:unknown"
                : "ip:" + request.getRemoteAddress().getAddress().getHostAddress();
    }

    private record Bucket(String name, int limit) {}
}
