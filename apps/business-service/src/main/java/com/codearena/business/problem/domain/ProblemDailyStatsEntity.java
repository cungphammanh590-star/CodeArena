package com.codearena.business.problem.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "problem_daily_stats")
@IdClass(ProblemDailyStatsEntity.Pk.class)
public class ProblemDailyStatsEntity {

    @Id
    @Column(name = "problem_id")
    private Integer problemId;

    @Id
    @Column(name = "stat_day")
    private LocalDate day;

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "accepted_today", nullable = false)
    private Integer acceptedToday = 0;

    @Column(name = "wrong_today", nullable = false)
    private Integer wrongToday = 0;

    @Column(name = "status_breakdown")
    private String statusBreakdown;

    @Column(name = "consecutive_days", nullable = false)
    private Integer consecutiveDays = 0;

    @Column(name = "is_new_today", nullable = false)
    private Boolean isNewToday = false;

    @Column(name = "is_review_today", nullable = false)
    private Boolean isReviewToday = false;

    @Column(name = "status_change", length = 32)
    private String statusChange;

    @Getter
    @Setter
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private Integer problemId;
        private LocalDate day;
    }
}
