package com.codearena.business.knowledge.srs.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_kp_srs")
@IdClass(UserKpSrsEntity.Pk.class)
public class UserKpSrsEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "knowledge_point_id", nullable = false)
    private Long knowledgePointId;

    @Column(nullable = false)
    private Float ease = 2.5f;

    @Column(name = "interval_days", nullable = false)
    private Integer intervalDays = 0;

    @Column(nullable = false)
    private Integer reps = 0;

    @Column(nullable = false)
    private Integer lapses = 0;

    @Column(name = "due_at", nullable = false)
    private OffsetDateTime dueAt;

    @Column(name = "last_outcome", length = 16)
    private String lastOutcome;

    @Column(name = "last_reviewed_at")
    private OffsetDateTime lastReviewedAt;

    @Column(nullable = false)
    private Boolean suspended = false;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (ease == null) {
            ease = 2.5f;
        }
        if (intervalDays == null) {
            intervalDays = 0;
        }
        if (reps == null) {
            reps = 0;
        }
        if (lapses == null) {
            lapses = 0;
        }
        if (suspended == null) {
            suspended = false;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    @Getter
    @Setter
    public static class Pk implements Serializable {
        private Long userId;
        private Long knowledgePointId;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Pk pk)) {
                return false;
            }
            return Objects.equals(userId, pk.userId)
                    && Objects.equals(knowledgePointId, pk.knowledgePointId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, knowledgePointId);
        }
    }
}
