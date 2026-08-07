package com.codearena.business.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_llm_settings")
public class UserLlmSettingsEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 16)
    private String provider = "ollama";

    @Column(name = "api_provider", nullable = false, length = 32)
    private String apiProvider = "";

    @Column(name = "coach_model", nullable = false, length = 128)
    private String coachModel = "";

    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl = "";

    /** Encrypted or raw key; never expose via public APIs. */
    @Column(name = "api_key_enc", nullable = false, columnDefinition = "TEXT")
    private String apiKeyEnc = "";

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
        if (provider == null || provider.isBlank()) {
            provider = "ollama";
        }
        if (apiProvider == null) {
            apiProvider = "";
        }
        if (coachModel == null) {
            coachModel = "";
        }
        if (baseUrl == null) {
            baseUrl = "";
        }
        if (apiKeyEnc == null) {
            apiKeyEnc = "";
        }
    }

    public boolean hasApiKey() {
        return apiKeyEnc != null && !apiKeyEnc.isBlank();
    }
}
