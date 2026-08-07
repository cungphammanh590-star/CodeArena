package com.codearena.business.user.web.external;

import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.service.CurrentUserService;
import com.codearena.business.user.service.UserLlmSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前用户自己的 LLM Key / 模型配置（按用户隔离）。 */
@RestController
@RequestMapping("/api/users/me/llm")
@RequiredArgsConstructor
public class UserLlmController {

    private final CurrentUserService currentUserService;
    private final UserLlmSettingsService llmSettingsService;

    @GetMapping
    public Map<String, Object> get(HttpServletRequest request) {
        UserEntity user = currentUserService.require(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("llm", llmSettingsService.publicView(user));
        body.put("user_public_id", user.getPublicId());
        return body;
    }

    @PostMapping("/config")
    public Map<String, Object> config(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
        UserEntity user = currentUserService.require(request);
        Map<String, Object> llm = llmSettingsService.update(user, body);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("llm", llm);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("config", cfg);
        resp.put("user_public_id", user.getPublicId());
        resp.put("message", "已保存当前用户的陪练模型配置");
        return resp;
    }

    @PostMapping("/test")
    public Map<String, Object> test(HttpServletRequest request) {
        UserEntity user = currentUserService.require(request);
        return llmSettingsService.probe(user);
    }

    @PostMapping("/clear-key")
    public Map<String, Object> clearKey(
            HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
        UserEntity user = currentUserService.require(request);
        boolean switchToOllama = body == null
                || body.get("switch_to_ollama") == null
                || Boolean.TRUE.equals(body.get("switch_to_ollama"));
        Map<String, Object> llm = llmSettingsService.clearKey(user, switchToOllama);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("llm", llm);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("config", cfg);
        resp.put("message", "已清除当前用户的 API Key");
        return resp;
    }
}
