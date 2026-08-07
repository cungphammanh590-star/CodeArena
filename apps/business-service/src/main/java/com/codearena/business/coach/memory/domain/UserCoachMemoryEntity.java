package com.codearena.business.coach.memory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
@Table(name = "user_coach_memories")
public class UserCoachMemoryEntity {

    public static final String KIND_PREFERENCE = "preference";
    public static final String KIND_WEAKNESS = "weakness";
    public static final String KIND_COACH_NOTE = "coach_note";
    public static final String KIND_GOAL = "goal";

    public static final String SOURCE_USER = "user";
    public static final String SOURCE_COACH = "coach";
    public static final String SOURCE_SYSTEM = "system";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    private String kind;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, length = 16)
    private String source = SOURCE_COACH;

    @Column(name = "problem_id")
    private Integer problemId;

    @Column(nullable = false)
    private Float confidence = 0.8f;

    @Column(nullable = false)
    private Boolean active = true;

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
        if (active == null) {
            active = true;
        }
        if (confidence == null) {
            confidence = 0.8f;
        }
        if (source == null || source.isBlank()) {
            source = SOURCE_COACH;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
