package com.codearena.business.learning.plan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "plan_daily_tasks")
public class PlanDailyTaskEntity {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_SKIPPED = "skipped";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "day_num", nullable = false)
    private Integer dayNum;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    /** JSON array of problem ids, e.g. [1,15,70] */
    @Column(name = "problem_ids", nullable = false)
    private String problemIds = "[]";

    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
