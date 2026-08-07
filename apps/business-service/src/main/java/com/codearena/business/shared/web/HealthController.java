package com.codearena.business.shared.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 进程探活。DB + 可选探测 llm-service（供前端 coach_available）。
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final DataSource dataSource;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(800))
            .build();

    @Value("${server.port:8090}")
    private int port;

    @Value("${server.address:0.0.0.0}")
    private String host;

    @Value("${codearena.llm.base-url:http://127.0.0.1:8091}")
    private String llmBaseUrl;

    @GetMapping("/health")
    public Map<String, Object> health() {
        boolean dbOk = true;
        try (Connection ignored = dataSource.getConnection()) {
            // connectivity check only
        } catch (Exception ex) {
            dbOk = false;
        }
        boolean coachOk = probeLlm();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbOk ? "ok" : "error");
        body.put("server", "business-service");
        body.put("db_connected", dbOk);
        body.put("port", port);
        body.put("host", host);
        // 前端 CoachView / Dashboard 依赖这些字段
        body.put("coach_available", coachOk);
        body.put("llm_provider", coachOk ? "ollama" : "unavailable");
        body.put("llm_base_url", llmBaseUrl);
        body.put("kg_imported", true);
        return body;
    }

    private boolean probeLlm() {
        String base = (llmBaseUrl == null || llmBaseUrl.isBlank())
                ? "http://127.0.0.1:8091"
                : llmBaseUrl.trim().replaceAll("/+$", "");
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/health"))
                    .timeout(Duration.ofMillis(1500))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return false;
            }
            String body = resp.body() == null ? "" : resp.body();
            return body.contains("\"status\"") && body.contains("ok");
        } catch (Exception ex) {
            return false;
        }
    }
}
