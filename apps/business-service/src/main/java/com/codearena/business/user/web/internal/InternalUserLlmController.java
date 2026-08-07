package com.codearena.business.user.web.internal;

import com.codearena.business.shared.security.InternalTokenGuard;
import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.service.UserLlmSettingsService;
import com.codearena.business.user.service.UserService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内网：llm-service 按用户取 LLM 配置（含明文 api_key）。
 * 路径 {@code /internal/users/**}；勿经公网 Gateway 暴露。
 */
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserLlmController {

    private final UserService userService;
    private final UserLlmSettingsService llmSettingsService;
    private final InternalTokenGuard internalTokenGuard;

    @GetMapping("/llm")
    public Map<String, Object> llmSecret(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-User-Public-Id", required = false) String userPublicId) {
        internalTokenGuard.assertValid(token);
        UserEntity user;
        if (userPublicId != null && !userPublicId.isBlank()) {
            user = userService.getByPublicId(userPublicId.trim());
        } else {
            user = userService.ensureDefaultUser();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("llm", llmSettingsService.secretView(user));
        return body;
    }
}
