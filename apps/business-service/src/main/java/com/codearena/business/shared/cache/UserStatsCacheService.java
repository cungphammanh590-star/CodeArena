package com.codearena.business.shared.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 用户维统计读投影（Cache-Aside）。Redis 不可用时静默降级为直查 DB。
 *
 * <p>一致性：写 submissions / mastery 后 {@link #invalidateUser(Long)}；miss 时回源。
 */
@Service
@RequiredArgsConstructor
public class UserStatsCacheService {

    private static final Logger log = LoggerFactory.getLogger(UserStatsCacheService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper;

    @Value("${codearena.cache.stats-enabled:true}")
    private boolean enabled;

    @Value("${codearena.cache.stats-ttl-seconds:3600}")
    private long ttlSeconds;

    public Optional<Map<String, Object>> getDashboard(Long userId, String date) {
        return getJson(dashboardKey(userId, date));
    }

    public void putDashboard(Long userId, String date, Map<String, Object> body) {
        putJson(dashboardKey(userId, date), body);
    }

    public Optional<Map<String, Object>> getPortrait(Long userId) {
        return getJson(portraitKey(userId));
    }

    public void putPortrait(Long userId, Map<String, Object> body) {
        putJson(portraitKey(userId), body);
    }

    public void invalidateUser(Long userId) {
        if (userId == null || !enabled) {
            return;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            // 删 portrait；dashboard 按日 key 用 pattern（用户量小可接受）
            redis.delete(portraitKey(userId));
            var keys = redis.keys("stats:u:" + userId + ":d:*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception ex) {
            log.debug("stats cache invalidate skipped: {}", ex.toString());
        }
    }

    private Optional<Map<String, Object>> getJson(String key) {
        if (!enabled) {
            return Optional.empty();
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return Optional.empty();
        }
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(raw, MAP_TYPE));
        } catch (Exception ex) {
            log.debug("stats cache get miss/error: {}", ex.toString());
            return Optional.empty();
        }
    }

    private void putJson(String key, Map<String, Object> body) {
        if (!enabled || body == null) {
            return;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            String raw = objectMapper.writeValueAsString(body);
            redis.opsForValue().set(key, raw, Duration.ofSeconds(Math.max(60, ttlSeconds)));
        } catch (Exception ex) {
            log.debug("stats cache put skipped: {}", ex.toString());
        }
    }

    private static String dashboardKey(Long userId, String date) {
        return "stats:u:" + userId + ":d:" + date;
    }

    private static String portraitKey(Long userId) {
        return "stats:u:" + userId + ":portrait";
    }
}
