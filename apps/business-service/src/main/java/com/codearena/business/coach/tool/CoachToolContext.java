package com.codearena.business.coach.tool;

import java.util.Collections;
import java.util.Map;

/** 单次工具执行上下文（由 Python 透传用户与会话身份）。 */
public record CoachToolContext(
        Long userId,
        String userPublicId,
        String sessionId,
        Integer problemId,
        Map<String, Object> params) {

    public CoachToolContext {
        params = params == null ? Map.of() : Collections.unmodifiableMap(params);
    }

    public Object param(String key) {
        return params.get(key);
    }

    public String paramString(String key) {
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public Integer paramInt(String key) {
        Object v = params.get(key);
        if (v == null || "".equals(v)) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(v));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
