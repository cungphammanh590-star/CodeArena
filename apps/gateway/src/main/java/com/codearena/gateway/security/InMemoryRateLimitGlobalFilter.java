package com.codearena.gateway.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 进程内简易限流（按客户端 IP）。生产多副本时应换 Redis RequestRateLimiter。
 *
 * <p>默认：60 次/分钟；{@code /api/coach/stream} 与 {@code /submit} 各 20 次/分钟。
 */
@Component
public class InMemoryRateLimitGlobalFilter implements GlobalFilter, Ordered {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Value("${codearena.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${codearena.rate-limit.default-per-minute:60}")
    private int defaultPerMinute;

    @Value("${codearena.rate-limit.stream-per-minute:20}")
    private int streamPerMinute;

    @Value("${codearena.rate-limit.submit-per-minute:20}")
    private int submitPerMinute;

    @Override
    public int getOrder() {
        // After JWT auth (-100) so identity headers exist; before most business filters
        return -50;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        if (path == null) {
            path = "";
        }
        if (path.startsWith("/actuator")
                || path.equals("/health")
                || path.startsWith("/fallback")) {
            return chain.filter(exchange);
        }

        int limit = defaultPerMinute;
        String bucket = "default";
        if (path.startsWith("/api/coach/stream")) {
            limit = streamPerMinute;
            bucket = "stream";
        } else if (path.equals("/submit") || path.startsWith("/submit/")) {
            limit = submitPerMinute;
            bucket = "submit";
        }

        String client = clientKey(request);
        String key = bucket + "|" + client;
        long minute = System.currentTimeMillis() / 60_000L;
        Window window = windows.compute(key, (k, existing) -> {
            if (existing == null || existing.minute != minute) {
                return new Window(minute, new AtomicInteger(0));
            }
            return existing;
        });
        int count = window.counter.incrementAndGet();
        if (count > limit) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().add("Retry-After", "60");
            return exchange.getResponse().setComplete();
        }
        // Opportunistic cleanup of stale keys
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> e.getValue().minute < minute - 1);
        }
        return chain.filter(exchange);
    }

    private static String clientKey(ServerHttpRequest request) {
        String user = request.getHeaders().getFirst("X-User-Public-Id");
        if (user != null && !user.isBlank()) {
            return "u:" + user.trim();
        }
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return "ip:" + forwarded.split(",")[0].trim();
        }
        if (request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null) {
            return "ip:" + request.getRemoteAddress().getAddress().getHostAddress();
        }
        return "ip:unknown";
    }

    private record Window(long minute, AtomicInteger counter) {}
}
