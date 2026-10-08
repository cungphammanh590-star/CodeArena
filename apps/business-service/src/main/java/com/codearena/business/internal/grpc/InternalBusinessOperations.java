package com.codearena.business.internal.grpc;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolRegistry;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.user.api.UserLookup;
import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.service.LlmUsageService;
import com.codearena.business.user.service.UserLlmSettingsService;
import com.codearena.business.user.service.UserService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Business behavior shared by the private gRPC methods; no HTTP endpoint. */
@Service
@RequiredArgsConstructor
public class InternalBusinessOperations {
    private final CoachToolRegistry registry;
    private final UserLookup userLookup;
    private final UserService userService;
    private final UserLlmSettingsService llmSettingsService;
    private final LlmUsageService llmUsageService;

    public Map<String, Object> listTools() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("tools", registry.catalog());
        response.put("note", "get_last_advice 由 Python 基于会话消息本地执行，不在此清单");
        return response;
    }

    public Map<String, Object> executeTool(
            String userPublicId, String toolName, Map<String, Object> params,
            String sessionId, Integer problemId) {
        if (toolName == null || toolName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tool_name required");
        }
        UserEntity user = userPublicId == null || userPublicId.isBlank()
                ? userLookup.ensureDefaultUser() : userLookup.getByPublicId(userPublicId.trim());
        Integer resolvedProblemId = problemId != null ? problemId : toInt(params.get("problem_id"));
        CoachTool tool = registry.require(toolName.trim());
        CoachToolResult result = tool.execute(new CoachToolContext(
                user.getId(), user.getPublicId(), sessionId, resolvedProblemId, params));
        Map<String, Object> response = new LinkedHashMap<>(result.toMap());
        response.put("tool_name", toolName.trim());
        response.put("kind", tool.kind().name());
        response.put("user_public_id", user.getPublicId());
        return response;
    }

    public Map<String, Object> userLlm(String userPublicId) {
        UserEntity user = resolveUser(userPublicId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("llm", llmSettingsService.secretView(user));
        return response;
    }

    public Map<String, Object> recordUsage(String userPublicId, Map<String, Object> body) {
        UserEntity user = resolveUser(userPublicId);
        var saved = llmUsageService.record(user, body);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("id", saved.getId());
        return response;
    }

    private UserEntity resolveUser(String userPublicId) {
        return userPublicId == null || userPublicId.isBlank()
                ? userService.ensureDefaultUser() : userService.getByPublicId(userPublicId.trim());
    }

    private static Integer toInt(Object value) {
        if (value == null || "".equals(value)) return null;
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
