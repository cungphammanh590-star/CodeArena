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
@Table(name = "study_plans")
public class StudyPlanEntity {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_PAUSED = "paused";
    public static final String STATUS_COMPLETED = "completed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "goal_type", nullable = false, length = 32)
    private String goalType;

    @Column(name = "goal_ref", nullable = false, length = 128)
    private String goalRef;

    @Column(length = 256)
    private String title;

    @Column(name = "list_id", length = 64)
    private String listId;

    @Column(name = "total_days")
    private Integer totalDays;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(nullable = false, length = 20)
    private String status = STATUS_ACTIVE;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
