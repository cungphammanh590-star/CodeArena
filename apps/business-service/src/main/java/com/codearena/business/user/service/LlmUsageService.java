package com.codearena.business.user.service;

import com.codearena.business.user.domain.LlmUsageEventEntity;
import com.codearena.business.user.domain.LlmUsageEventRepository;
import com.codearena.business.user.domain.UserEntity;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LlmUsageService {

    private final LlmUsageEventRepository repository;

    @Transactional
    public LlmUsageEventEntity record(UserEntity user, Map<String, Object> body) {
        LlmUsageEventEntity e = new LlmUsageEventEntity();
        e.setUserId(user.getId());
        e.setSessionId(trimTo(str(body.get("session_id")), 64));
        e.setRequestId(trimTo(str(body.get("request_id")), 64));
        e.setProvider(trimTo(str(body.get("provider")), 32));
        e.setApiProvider(trimTo(str(body.get("api_provider")), 32));
        e.setModel(trimTo(str(body.get("model")), 128));
        int prompt = intVal(body.get("prompt_tokens"));
        int completion = intVal(body.get("completion_tokens"));
        int total = intVal(body.get("total_tokens"));
        if (total <= 0) {
            total = Math.max(0, prompt) + Math.max(0, completion);
        }
        e.setPromptTokens(Math.max(0, prompt));
        e.setCompletionTokens(Math.max(0, completion));
        e.setTotalTokens(Math.max(0, total));
        e.setSuccess(body.get("success") == null || Boolean.TRUE.equals(body.get("success")));
        e.setErrorCode(trimTo(str(body.get("error_code")), 64));
        e.setCreatedAt(Instant.now());
        return repository.save(e);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summaryFor(UserEntity user, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        Instant dayAgo = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("last_24h", packSum(repository.sumTokensSince(user.getId(), dayAgo), repository.countByUserIdAndCreatedAtGreaterThanEqual(user.getId(), dayAgo)));
        out.put("last_7d", packSum(repository.sumTokensSince(user.getId(), weekAgo), repository.countByUserIdAndCreatedAtGreaterThanEqual(user.getId(), weekAgo)));
        List<LlmUsageEventEntity> recent =
                repository.findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(0, safeLimit));
        List<Map<String, Object>> items = new ArrayList<>();
        for (LlmUsageEventEntity e : recent) {
            items.add(toView(e));
        }
        out.put("recent", items);
        return out;
    }

    private static Map<String, Object> packSum(Long tokens, long calls) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_tokens", tokens == null ? 0L : tokens);
        m.put("calls", calls);
        return m;
    }

    private static Map<String, Object> toView(LlmUsageEventEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("session_id", e.getSessionId());
        m.put("provider", e.getProvider());
        m.put("api_provider", e.getApiProvider());
        m.put("model", e.getModel());
        m.put("prompt_tokens", e.getPromptTokens());
        m.put("completion_tokens", e.getCompletionTokens());
        m.put("total_tokens", e.getTotalTokens());
        m.put("success", e.isSuccess());
        m.put("error_code", e.getErrorCode());
        m.put("created_at", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        return m;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String trimTo(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static int intVal(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
