package com.codearena.business.ops.web;

import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.service.CurrentUserService;
import com.codearena.business.user.service.UserLlmSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OpsController {

    private final CurrentUserService currentUserService;
    private final UserLlmSettingsService llmSettingsService;

    @GetMapping("/api/ops/config")
    public Map<String, Object> config(HttpServletRequest request) {
        UserEntity user = currentUserService.require(request);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("host", "0.0.0.0");
        cfg.put("port", 8090);
        cfg.put(
                "learning",
                Map.of(
                        "list_mode", true,
                        "kg_mode", true,
                        "active_list_id", "hot100"));
        cfg.put("llm", llmSettingsService.publicView(user));
        cfg.put("db_path_readonly", "postgresql://localhost:5432/codearena");
        cfg.put("user_public_id", user.getPublicId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("config", cfg);
        body.put("note", "llm 配置按当前用户隔离（X-User-Public-Id / default）");
        return body;
    }

    @GetMapping("/api/ops/kg")
    public Map<String, Object> kgStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("imported", false);
        body.put("tracks", 0);
        body.put("nodes", 0);
        body.put("problems", 0);
        body.put("message", "KG stubs — import via llm-service / future job");
        return body;
    }

    @PostMapping("/api/ops/kg/import")
    public ResponseEntity<?> kgImport(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null || !Boolean.TRUE.equals(body.get("confirm"))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "confirm=true required"));
        }
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "imported", false,
                "message", "stub: KG import not implemented in business-service"));
    }

    @PostMapping("/api/ops/logs/clean")
    public ResponseEntity<?> cleanLogs(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null || !Boolean.TRUE.equals(body.get("confirm"))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "confirm=true required"));
        }
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "service_logs", List.of(),
                "coach_debug_logs", List.of(),
                "count", 0));
    }

    @PostMapping("/api/ops/stats/rebuild")
    public ResponseEntity<?> rebuildStats(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null || !Boolean.TRUE.equals(body.get("confirm"))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "confirm=true required"));
        }
        boolean fromScratch = body.get("from_scratch") == null
                || Boolean.TRUE.equals(body.get("from_scratch"));
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "problems", 0,
                "from_scratch", fromScratch,
                "message", "stub: rebuild will be implemented against submissions"));
    }

    /** LLM 配置按当前用户持久化；推理仍在 llm-service。 */
    @PostMapping("/api/ops/llm/config")
    public Map<String, Object> llmConfig(
            HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
        UserEntity user = currentUserService.require(request);
        Map<String, Object> llm = llmSettingsService.update(user, body);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("llm", llm);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("config", cfg);
        resp.put("owner", "business-service");
        resp.put("user_public_id", user.getPublicId());
        resp.put("message", "已保存当前用户的陪练模型配置");
        return resp;
    }

    @PostMapping("/api/ops/llm/test")
    public Map<String, Object> llmTest(HttpServletRequest request) {
        UserEntity user = currentUserService.require(request);
        Map<String, Object> result = new LinkedHashMap<>(llmSettingsService.probe(user));
        result.put("owner", "business-service");
        result.put("user_public_id", user.getPublicId());
        return result;
    }

    @PostMapping("/api/ops/llm/clear-key")
    public ResponseEntity<?> llmClearKey(
            HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
        if (body == null || !Boolean.TRUE.equals(body.get("confirm"))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "confirm=true required"));
        }
        UserEntity user = currentUserService.require(request);
        boolean switchOllama = body.get("switch_to_ollama") == null
                || Boolean.TRUE.equals(body.get("switch_to_ollama"));
        Map<String, Object> llm = llmSettingsService.clearKey(user, switchOllama);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("llm", llm);
        return ResponseEntity.ok(Map.of(
                "status",
                "ok",
                "config",
                cfg,
                "user_public_id",
                user.getPublicId(),
                "message",
                "已清除 API Key" + (switchOllama ? "，并切回 Ollama" : ""),
                "owner",
                "business-service"));
    }
}
