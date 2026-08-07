package com.codearena.business.problem.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "problem_stats")
public class ProblemStatsEntity {

    @Id
    @Column(name = "problem_id")
    private Integer problemId;

    private String title;

    @Column(name = "title_slug")
    private String titleSlug;

    @Column(length = 16)
    private String difficulty;

    @Column(name = "topic_tags")
    private String topicTags;

    @Column(name = "total_attempts", nullable = false)
    private Integer totalAttempts = 0;

    @Column(name = "accepted_count", nullable = false)
    private Integer acceptedCount = 0;

    @Column(name = "wrong_count", nullable = false)
    private Integer wrongCount = 0;

    @Column(name = "status_breakdown")
    private String statusBreakdown;

    @Column(name = "first_attempt_at")
    private OffsetDateTime firstAttemptAt;

    @Column(name = "last_attempt_at")
    private OffsetDateTime lastAttemptAt;

    @Column(name = "first_accepted_at")
    private OffsetDateTime firstAcceptedAt;

    @Column(name = "acceptance_rate", nullable = false)
    private Double acceptanceRate = 0.0;

    @Column(name = "struggle_score", nullable = false)
    private Double struggleScore = 0.0;

    @Column(name = "solve_time_seconds")
    private Integer solveTimeSeconds;

    @Column(name = "avg_attempts_to_ac")
    private Double avgAttemptsToAc;

    @Column(name = "attempts_at_last_ac", nullable = false)
    private Integer attemptsAtLastAc = 0;

    @Column(name = "last_status")
    private String lastStatus;

    @Column(name = "last_submitted_at")
    private OffsetDateTime lastSubmittedAt;

    @Column(name = "llm_summary")
    private String llmSummary;

    @Column(name = "common_pitfall")
    private String commonPitfall;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
