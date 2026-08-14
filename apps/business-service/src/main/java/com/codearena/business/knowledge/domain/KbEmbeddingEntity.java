package com.codearena.business.knowledge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "kb_embeddings")
public class KbEmbeddingEntity {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_STALE = "stale";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "knowledge_point_id", nullable = false)
    private Long knowledgePointId;

    @Column(name = "embedding_model", nullable = false, length = 128)
    private String embeddingModel;

    @Column(name = "embedding_version", nullable = false, length = 64)
    private String embeddingVersion;

    @Column(name = "qdrant_point_id", nullable = false, length = 64)
    private String qdrantPointId;

    @Column(nullable = false, length = 32)
    private String status = STATUS_ACTIVE;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (status == null || status.isBlank()) {
            status = STATUS_ACTIVE;
        }
    }
}
