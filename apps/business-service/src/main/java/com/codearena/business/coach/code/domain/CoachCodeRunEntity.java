package com.codearena.business.coach.code.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "coach_code_runs")
public class CoachCodeRunEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "problem_id")
    private Integer problemId;

    @Column(nullable = false, length = 16)
    private String language;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "timed_out", nullable = false)
    private Boolean timedOut = false;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "snippet_hash", length = 80)
    private String snippetHash;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
