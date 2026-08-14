package com.codearena.business.user.service;

import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.domain.UserLlmSettingsEntity;
import com.codearena.business.user.domain.UserLlmSettingsRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 按用户隔离的 LLM 配置。公开 API 只返回 has_api_key / 掩码；明文 Key 仅内网给 llm-service。
 */
@Service
@RequiredArgsConstructor
public class UserLlmSettingsService {

    private static final String ENC_PREFIX = "v1:";

    private final UserLlmSettingsRepository repository;

    @Value("${codearena.llm.key-secret:}")
    private String keySecret;

    public Map<String, Object> publicView(UserEntity user) {
        UserLlmSettingsEntity row = ensure(user.getId());
        return toPublicLlm(row);
    }

    /** 内网：含明文 api_key，供 llm-service 构建 Chat 模型。 */
    public Map<String, Object> secretView(UserEntity user) {
        UserLlmSettingsEntity row = ensure(user.getId());
        Map<String, Object> llm = toPublicLlm(row);
        llm.put("api_key", decrypt(row.getApiKeyEnc()));
        llm.put("user_public_id", user.getPublicId());
        return llm;
    }

    @Transactional
    public Map<String, Object> update(UserEntity user, Map<String, Object> body) {
        UserLlmSettingsEntity row = ensure(user.getId());
        if (body == null) {
            body = Map.of();
        }
        String provider = str(body.get("provider"));
        if (provider != null && !provider.isBlank()) {
            provider = provider.trim().toLowerCase();
            if (!provider.equals("ollama") && !provider.equals("api") && !provider.equals("mock")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider must be ollama|api|mock");
            }
            row.setProvider(provider);
        }
        String apiProvider = str(body.get("api_provider"));
        if (apiProvider != null) {
            row.setApiProvider(apiProvider.trim().toLowerCase());
        } else if ("api".equals(row.getProvider()) && (row.getApiProvider() == null || row.getApiProvider().isBlank())) {
            row.setApiProvider("deepseek");
        }
        String model = str(body.get("coach_model"));
        if (model != null) {
            row.setCoachModel(model.trim());
        }
        String baseUrl = str(body.get("base_url"));
        if (baseUrl != null) {
            row.setBaseUrl(baseUrl.trim());
        }
        String apiKey = str(body.get("api_key"));
        if (apiKey != null && !apiKey.isBlank() && !"**".equals(apiKey) && !apiKey.startsWith("***")) {
            row.setApiKeyEnc(encrypt(apiKey.trim()));
        }
        if ("api".equals(row.getProvider()) && (row.getCoachModel() == null || row.getCoachModel().isBlank())) {
            row.setCoachModel("deepseek-chat");
        }
        if ("ollama".equals(row.getProvider()) && (row.getCoachModel() == null || row.getCoachModel().isBlank())) {
            row.setCoachModel("qwen2.5:7b-instruct-q4_K_M");
        }
        repository.save(row);
        return toPublicLlm(row);
    }

    @Transactional
    public Map<String, Object> clearKey(UserEntity user, boolean switchToOllama) {
        UserLlmSettingsEntity row = ensure(user.getId());
        row.setApiKeyEnc("");
        if (switchToOllama) {
            row.setProvider("ollama");
            row.setApiProvider("");
            if (row.getCoachModel() == null || row.getCoachModel().isBlank() || "deepseek-chat".equals(row.getCoachModel())) {
                row.setCoachModel("qwen2.5:7b-instruct-q4_K_M");
            }
        }
        repository.save(row);
        return toPublicLlm(row);
    }

    /** 用该用户已保存配置做一次极短探测（不读请求体里的临时 Key）。 */
    public Map<String, Object> probe(UserEntity user) {
        UserLlmSettingsEntity row = ensure(user.getId());
        String provider = row.getProvider() == null ? "ollama" : row.getProvider();
        if ("api".equals(provider) && !row.hasApiKey()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先保存 API Key 后再测试");
        }
        if ("mock".equals(provider)) {
            return Map.of(
                    "status", "ok",
                    "provider", "mock",
                    "coach_model", row.getCoachModel(),
                    "reply_preview", "ok");
        }
        try {
            String preview;
            if ("api".equals(provider)) {
                preview = probeOpenAiCompatible(row);
            } else {
                preview = probeOllama(row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "ok");
            out.put("provider", provider);
            out.put("api_provider", row.getApiProvider());
            out.put("coach_model", row.getCoachModel());
            out.put("reply_preview", preview);
            return out;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "LLM probe failed: " + ex.getMessage());
        }
    }

    private String probeOpenAiCompatible(UserLlmSettingsEntity row) throws Exception {
        String base = row.getBaseUrl() == null || row.getBaseUrl().isBlank()
                ? "https://api.deepseek.com"
                : row.getBaseUrl().replaceAll("/$", "");
        String model = row.getCoachModel() == null || row.getCoachModel().isBlank()
                ? "deepseek-chat"
                : row.getCoachModel();
        String key = decrypt(row.getApiKeyEnc());
        String body =
                "{\"model\":\""
                        + escapeJson(model)
                        + "\",\"messages\":[{\"role\":\"user\",\"content\":\"只回复：ok\"}],\"max_tokens\":8}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/chat/completions"))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp =
                HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "upstream " + resp.statusCode() + ": " + trim(resp.body(), 200));
        }
        return trim(resp.body(), 120);
    }

    private String probeOllama(UserLlmSettingsEntity row) throws Exception {
        String base = row.getBaseUrl() == null || row.getBaseUrl().isBlank()
                ? "http://127.0.0.1:11434"
                : row.getBaseUrl().replaceAll("/$", "");
        String model = row.getCoachModel() == null || row.getCoachModel().isBlank()
                ? "qwen2.5:7b-instruct-q4_K_M"
                : row.getCoachModel();
        String body =
                "{\"model\":\""
                        + escapeJson(model)
                        + "\",\"messages\":[{\"role\":\"user\",\"content\":\"只回复：ok\"}],\"stream\":false}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/chat"))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp =
                HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "ollama " + resp.statusCode() + ": " + trim(resp.body(), 200));
        }
        return trim(resp.body(), 120);
    }

    private UserLlmSettingsEntity ensure(Long userId) {
        UserLlmSettingsEntity row = repository
                .findById(userId)
                .orElseGet(() -> {
                    UserLlmSettingsEntity created = new UserLlmSettingsEntity();
                    created.setUserId(userId);
                    created.setProvider("ollama");
                    created.setCoachModel("qwen2.5:7b-instruct-q4_K_M");
                    return repository.save(created);
                });
        if (row.getApiKeyEnc() != null && !row.getApiKeyEnc().isBlank()
                && !row.getApiKeyEnc().startsWith(ENC_PREFIX) && secretBytes() != null) {
            row.setApiKeyEnc(encrypt(row.getApiKeyEnc()));
            row = repository.save(row);
        }
        return row;
    }

    private Map<String, Object> toPublicLlm(UserLlmSettingsEntity row) {
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("provider", row.getProvider());
        llm.put("api_provider", row.getApiProvider());
        llm.put("coach_model", row.getCoachModel());
        llm.put("base_url", row.getBaseUrl());
        llm.put("has_api_key", row.hasApiKey());
        llm.put("api_key", row.hasApiKey() ? mask(decrypt(row.getApiKeyEnc())) : "");
        return llm;
    }

    String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return "";
        }
        byte[] key = secretBytes();
        if (key == null) {
            return plain;
        }
        try {
            byte[] iv = new byte[12];
            java.security.SecureRandom.getInstanceStrong().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ct, 0, packed, iv.length, ct.length);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (Exception ex) {
            throw new IllegalStateException("encrypt api_key failed", ex);
        }
    }

    String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            return "";
        }
        if (!stored.startsWith(ENC_PREFIX)) {
            return stored;
        }
        byte[] key = secretBytes();
        if (key == null) {
            throw new IllegalStateException("encrypted api_key present but codearena.llm.key-secret unset");
        }
        try {
            byte[] packed = Base64.getDecoder().decode(stored.substring(ENC_PREFIX.length()));
            byte[] iv = new byte[12];
            byte[] ct = new byte[packed.length - 12];
            System.arraycopy(packed, 0, iv, 0, 12);
            System.arraycopy(packed, 12, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("decrypt api_key failed", ex);
        }
    }

    private byte[] secretBytes() {
        if (keySecret == null || keySecret.isBlank()) {
            return null;
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(keySecret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String mask(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (key.length() <= 8) {
            return "***";
        }
        return key.substring(0, 3) + "***" + key.substring(key.length() - 4);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
