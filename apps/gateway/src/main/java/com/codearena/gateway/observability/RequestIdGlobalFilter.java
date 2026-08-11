package com.codearena.gateway.observability;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 生成/透传 X-Request-Id，写入 MDC，并转发到下游（含 sw8 原样保留）。
 */
@Component
public class RequestIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String HDR_REQUEST_ID = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "request_id";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HDR_REQUEST_ID);
        String requestId =
                (incoming == null || incoming.isBlank()) ? UUID.randomUUID().toString() : incoming.trim();

        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(HDR_REQUEST_ID, requestId)
                .build();
        exchange.getResponse().getHeaders().set(HDR_REQUEST_ID, requestId);

        MDC.put(MDC_REQUEST_ID, requestId);
        return chain.filter(exchange.mutate().request(mutated).build())
                .doFinally(signal -> MDC.remove(MDC_REQUEST_ID));
    }
}
