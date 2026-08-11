package com.codearena.business.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "llm_usage_events")
public class LlmUsageEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(nullable = false, length = 32)
    private String provider = "";

    @Column(name = "api_provider", nullable = false, length = 32)
    private String apiProvider = "";

    @Column(nullable = false, length = 128)
    private String model = "";

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private int totalTokens;

    @Column(nullable = false)
    private boolean success = true;

    @Column(name = "error_code", nullable = false, length = 64)
    private String errorCode = "";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
