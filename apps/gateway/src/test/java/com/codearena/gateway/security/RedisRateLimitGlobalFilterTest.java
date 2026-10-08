package com.codearena.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class RedisRateLimitGlobalFilterTest {

    private FakeRedisTemplate redis;
    private final AtomicInteger chainCalls = new AtomicInteger();
    private final GatewayFilterChain chain = exchange -> {
        chainCalls.incrementAndGet();
        return Mono.empty();
    };
    private SimpleMeterRegistry meters;
    private RedisRateLimitGlobalFilter filter;

    @BeforeEach
    void setUp() {
        redis = new FakeRedisTemplate();
        chainCalls.set(0);
        meters = new SimpleMeterRegistry();
        filter = new RedisRateLimitGlobalFilter(redis, meters);
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "defaultLimit", 60);
        ReflectionTestUtils.setField(filter, "streamLimit", 20);
        ReflectionTestUtils.setField(filter, "submitLimit", 20);
        ReflectionTestUtils.setField(filter, "authLimit", 10);
        ReflectionTestUtils.setField(filter, "exportLimit", 5);
    }

    @Test
    void allowsRequestAndPublishesRemainingQuota() {
        redis.result = Flux.just(3L);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/api/coach/stream")
                        .header(JwtAuthGlobalFilter.HDR_PUBLIC_ID, "user-1")
                        .build());

        filter.filter(exchange, chain).block();

        assertThat(chainCalls).hasValue(1);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("20");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("17");
        assertThat(meters.get("codearena.gateway.rate_limit.requests")
                        .tags("bucket", "stream", "outcome", "allowed")
                        .counter().count())
                .isEqualTo(1);
    }

    @Test
    void rejectsRequestAfterSharedLimitIsExceeded() {
        redis.result = Flux.just(11L);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.post("/api/auth/login").build());

        filter.filter(exchange, chain).block();

        assertThat(chainCalls).hasValue(0);
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(exchange.getResponse().getHeaders().getFirst("Retry-After")))
                .isBetween(1L, 60L);
    }

    @Test
    void failsOpenAndRecordsMetricWhenRedisIsUnavailable() {
        redis.result = Flux.error(new IllegalStateException("redis down"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/api/problems").build());

        filter.filter(exchange, chain).block();

        assertThat(chainCalls).hasValue(1);
        assertThat(meters.get("codearena.gateway.rate_limit.requests")
                        .tags("bucket", "default", "outcome", "fail_open")
                        .counter().count())
                .isEqualTo(1);
    }

    private static final class FakeRedisTemplate extends ReactiveStringRedisTemplate {
        private Flux<Long> result = Flux.empty();

        FakeRedisTemplate() {
            super((ReactiveRedisConnectionFactory) Proxy.newProxyInstance(
                    ReactiveRedisConnectionFactory.class.getClassLoader(),
                    new Class<?>[] {ReactiveRedisConnectionFactory.class},
                    (proxy, method, args) -> null));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Flux<T> execute(RedisScript<T> script, List<String> keys, List<?> args) {
            return (Flux<T>) result;
        }
    }
}
