package com.codearena.business.coach.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** 工具执行结果；序列化后回给 LangGraph。 */
public record CoachToolResult(boolean ok, Map<String, Object> data, String note) {

    public static CoachToolResult success(Map<String, Object> data) {
        return new CoachToolResult(true, data == null ? Map.of() : data, null);
    }

    public static CoachToolResult failure(String note) {
        return new CoachToolResult(false, Map.of(), note);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", ok);
        if (note != null) {
            body.put("note", note);
        }
        if (data != null) {
            body.putAll(data);
        }
        return body;
    }
}
