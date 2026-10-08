package com.codearena.business.shared.web;

import com.codearena.business.internal.grpc.HealthRequest;
import com.codearena.business.internal.grpc.LlmHealthGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

    private ManagedChannel llmChannel;

    @Value("${server.port:8090}")
    private int port;

    @Value("${server.address:0.0.0.0}")
    private String host;

    @Value("${codearena.llm.base-url:http://127.0.0.1:8091}")
    private String llmBaseUrl;

    @Value("${codearena.llm.grpc-target:127.0.0.1:9093}")
    private String llmGrpcTarget;

    @Value("${codearena.knowledge.enabled:false}")
    private boolean knowledgeEnabled;

    @Value("${codearena.mail.enabled:false}")
    private boolean mailEnabled;

    @PostConstruct
    void connectLlmHealth() {
        llmChannel = ManagedChannelBuilder.forTarget(llmGrpcTarget).usePlaintext().build();
    }

    @PreDestroy
    void closeLlmHealth() {
        if (llmChannel != null) llmChannel.shutdown();
    }

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
        body.put("llm_provider", coachOk ? "user-api-key" : "unavailable");
        body.put("llm_base_url", llmBaseUrl);
        body.put("kg_imported", true);
        body.put("capabilities", Map.of(
                "nex", coachOk,
                "knowledge", knowledgeEnabled,
                "email_export", mailEnabled));
        return body;
    }

    private boolean probeLlm() {
        try {
            return LlmHealthGrpc.newBlockingStub(llmChannel)
                    .withDeadlineAfter(1500, TimeUnit.MILLISECONDS)
                    .check(HealthRequest.getDefaultInstance())
                    .getReady();
        } catch (Exception ex) {
            return false;
        }
    }
}
