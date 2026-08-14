package com.codearena.business.knowledge.ingest;

import com.codearena.business.knowledge.config.KnowledgeProperties;
import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.domain.UserRepository;
import com.codearena.business.user.service.UserLlmSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 可选 LLM JSON 精炼；失败时由调用方回退规则结果。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeLlmRefiner {

    private final KnowledgeProperties properties;
    private final KnowledgePointRefiner ruleRefiner;
    private final UserLlmSettingsService llmSettingsService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public KnowledgePointRefiner.RefinedKp refine(
            Long userId, KnowledgePointExtractor.ExtractedKp raw) {
        KnowledgePointRefiner.RefinedKp fallback = ruleRefiner.refineRule(raw);
        if (!properties.isLlmRefineEnabled()) {
            return fallback;
        }
        try {
            String content = callChat(userId, buildPrompt(raw));
            KnowledgePointRefiner.RefinedKp parsed = parse(content, fallback);
            return parsed != null ? parsed : fallback;
        } catch (Exception e) {
            log.warn("llm refine failed, fallback to rules: {}", e.toString());
            return fallback;
        }
    }

    private String buildPrompt(KnowledgePointExtractor.ExtractedKp raw) {
        return """
                你是 CS 面试知识点精炼器。从候选文本提取结构化闪卡，去掉广告/训练营/番外/引流。
                只输出 JSON（不要 markdown）：
                {"keep":true,"title":"...","question":"...","answer":"...","key_points":["..."],"topic":"mysql|redis|jvm|java-concurrency|spring|null"}
                若整段是广告或无关内容，keep=false。

                候选标题: %s
                候选正文:
                %s
                """
                .formatted(nullToEmpty(raw.title()), nullToEmpty(raw.body()));
    }

    private KnowledgePointRefiner.RefinedKp parse(
            String content, KnowledgePointRefiner.RefinedKp fallback) throws Exception {
        String json = extractJson(content);
        JsonNode n = objectMapper.readTree(json);
        boolean keep = !n.path("keep").isBoolean() || n.path("keep").asBoolean(true);
        if (!keep) {
            return new KnowledgePointRefiner.RefinedKp(
                    false, fallback.title(), fallback.question(), fallback.answer(), List.of(), null, true);
        }
        String title = text(n, "title", fallback.title());
        String question = text(n, "question", title);
        String answer = text(n, "answer", fallback.answer());
        if (answer == null || answer.length() < 16) {
            return fallback;
        }
        List<String> keys = new ArrayList<>();
        if (n.path("key_points").isArray()) {
            for (JsonNode k : n.path("key_points")) {
                if (k.isTextual() && !k.asText().isBlank()) {
                    keys.add(k.asText().trim());
                }
            }
        }
        String topic = n.path("topic").isNull() ? fallback.topic() : n.path("topic").asText(null);
        if (topic != null && ("null".equalsIgnoreCase(topic) || topic.isBlank())) {
            topic = fallback.topic();
        }
        return new KnowledgePointRefiner.RefinedKp(true, title, question, answer, keys, topic, true);
    }

    private String callChat(Long userId, String prompt) throws Exception {
        KnowledgeProperties.Llm llm = properties.getLlm();
        String url = llm.getHttpUrl();
        String key = llm.getHttpApiKey();
        String model = llm.getModel();

        if (url == null || url.isBlank() || key == null || key.isBlank()) {
            // 回退用户 LLM 设置
            UserEntity user = userRepository
                    .findById(userId)
                    .orElseThrow(() -> new IllegalStateException("user not found for refine"));
            Map<String, Object> secret = llmSettingsService.secretView(user);
            String provider = String.valueOf(secret.getOrDefault("provider", "mock"));
            if ("mock".equals(provider)) {
                throw new IllegalStateException("no llm configured for refine");
            }
            model = String.valueOf(secret.getOrDefault("coach_model", "deepseek-chat"));
            key = String.valueOf(secret.getOrDefault("api_key", ""));
            String base = String.valueOf(secret.getOrDefault("base_url", ""));
            if ("ollama".equals(provider)) {
                return callOllama(base, model, prompt);
            }
            if (base == null || base.isBlank()) {
                base = "https://api.deepseek.com";
            }
            url = base.replaceAll("/$", "") + "/chat/completions";
        }

        String body = objectMapper.writeValueAsString(Map.of(
                "model", model == null || model.isBlank() ? "deepseek-chat" : model,
                "temperature", 0.1,
                "messages",
                List.of(
                        Map.of("role", "system", "content", "只输出合法 JSON。"),
                        Map.of("role", "user", "content", prompt))));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp =
                HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("chat " + resp.statusCode() + ": " + trim(resp.body(), 200));
        }
        JsonNode root = objectMapper.readTree(resp.body());
        return root.path("choices").path(0).path("message").path("content").asText("");
    }

    private String callOllama(String base, String model, String prompt) throws Exception {
        String b = (base == null || base.isBlank()) ? "http://127.0.0.1:11434" : base.replaceAll("/$", "");
        String body = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "stream", false,
                "messages", List.of(Map.of("role", "user", "content", prompt))));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(b + "/api/chat"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp =
                HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("ollama " + resp.statusCode());
        }
        return objectMapper.readTree(resp.body()).path("message").path("content").asText("");
    }

    private static String extractJson(String content) {
        if (content == null) {
            return "{}";
        }
        String t = content.trim();
        int a = t.indexOf('{');
        int b = t.lastIndexOf('}');
        if (a >= 0 && b > a) {
            return t.substring(a, b + 1);
        }
        return t;
    }

    private static String text(JsonNode n, String field, String fallback) {
        String v = n.path(field).asText(null);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        return v.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
