package com.codearena.business.coach.memory.domain;

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
@Table(name = "coach_sessions")
public class CoachSessionEntity {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_CLOSED = "closed";

    @Id
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "thread_id", nullable = false, length = 96)
    private String threadId;

    @Column(name = "problem_id")
    private Integer problemId;

    @Column(name = "submission_id", length = 128)
    private String submissionId;

    @Column(nullable = false, length = 32)
    private String mode = "default";

    @Column(nullable = false, length = 64)
    private String topic = "";

    @Column(name = "session_kind", nullable = false, length = 16)
    private String sessionKind = "lobby";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary = "";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String opening = "";

    @Column(nullable = false, length = 32)
    private String phase = "lobby";

    @Column(nullable = false, length = 16)
    private String status = STATUS_ACTIVE;

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
        if (threadId == null || threadId.isBlank()) {
            threadId = sessionId;
        }
        if (status == null) {
            status = STATUS_ACTIVE;
        }
        if (phase == null) {
            phase = "lobby";
        }
        if (mode == null) {
            mode = "default";
        }
        if (topic == null) {
            topic = "";
        }
        if (sessionKind == null || sessionKind.isBlank()) {
            sessionKind = "lobby";
        }
        if (summary == null) {
            summary = "";
        }
        if (opening == null) {
            opening = "";
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
