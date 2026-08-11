package com.codearena.business.user.web.internal;

import com.codearena.business.shared.security.InternalTokenGuard;
import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.service.LlmUsageService;
import com.codearena.business.user.service.UserService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内网：llm-service 上报模型用量。 */
@RestController
@RequestMapping("/internal/llm")
@RequiredArgsConstructor
public class InternalLlmUsageController {

    private final InternalTokenGuard internalTokenGuard;
    private final UserService userService;
    private final LlmUsageService llmUsageService;

    @PostMapping("/usage")
    public Map<String, Object> record(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-User-Public-Id", required = false) String userPublicId,
            @RequestBody(required = false) Map<String, Object> body) {
        internalTokenGuard.assertValid(token);
        UserEntity user;
        if (userPublicId != null && !userPublicId.isBlank()) {
            user = userService.getByPublicId(userPublicId.trim());
        } else {
            user = userService.ensureDefaultUser();
        }
        var saved = llmUsageService.record(user, body == null ? Map.of() : body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("id", saved.getId());
        return out;
    }
}
