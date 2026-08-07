package com.codearena.business.coach.web.internal;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolRegistry;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.shared.security.InternalTokenGuard;
import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.api.UserLookup;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 内网工具执行入口（仅供 llm-service 回调）。路径前缀 {@code /internal/tools/**}。
 * 勿经公网 Gateway 暴露。
 */
@RestController
@RequestMapping("/internal/tools")
@RequiredArgsConstructor
public class InternalToolController {

    private final CoachToolRegistry registry;
    private final UserLookup userLookup;
    private final InternalTokenGuard internalTokenGuard;

    @GetMapping("/catalog")
    public Map<String, Object> catalog(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        internalTokenGuard.assertValid(token);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("tools", registry.catalog());
        body.put(
                "note",
                "get_last_advice 由 Python 基于会话消息本地执行，不在此清单");
        return body;
    }

    @PostMapping("/exec")
    public ResponseEntity<Map<String, Object>> exec(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-User-Public-Id", required = false) String userPublicId,
            @RequestBody Map<String, Object> body) {
        internalTokenGuard.assertValid(token);
        if (body == null || body.get("tool_name") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tool_name required");
        }
        String toolName = String.valueOf(body.get("tool_name")).trim();
        @SuppressWarnings("unchecked")
        Map<String, Object> params = body.get("params") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();

        UserEntity user = resolveUser(userPublicId);
        String sessionId = body.get("session_id") == null
                ? null
                : String.valueOf(body.get("session_id"));
        Integer problemId = toInt(body.get("problem_id"));
        if (problemId == null) {
            problemId = toInt(params.get("problem_id"));
        }

        CoachTool tool = registry.require(toolName);
        CoachToolContext ctx =
                new CoachToolContext(user.getId(), user.getPublicId(), sessionId, problemId, params);
        CoachToolResult result = tool.execute(ctx);

        Map<String, Object> resp = new LinkedHashMap<>(result.toMap());
        resp.put("tool_name", toolName);
        resp.put("kind", tool.kind().name());
        resp.put("user_public_id", user.getPublicId());
        return ResponseEntity.ok(resp);
    }

    private UserEntity resolveUser(String userPublicId) {
        if (userPublicId != null && !userPublicId.isBlank()) {
            return userLookup.getByPublicId(userPublicId.trim());
        }
        return userLookup.ensureDefaultUser();
    }

    private static Integer toInt(Object o) {
        if (o == null || "".equals(o)) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(o));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
