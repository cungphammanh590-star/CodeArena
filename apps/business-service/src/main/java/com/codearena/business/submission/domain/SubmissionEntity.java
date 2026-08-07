package com.codearena.business.submission.domain;

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
@Table(name = "submissions")
public class SubmissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "submission_id", nullable = false, unique = true, length = 64)
    private String submissionId;

    @Column(name = "problem_id", nullable = false)
    private Integer problemId;

    @Column(nullable = false)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String code;

    @Column(name = "runtime_ms")
    private Integer runtimeMs;

    @Column(name = "memory_mb")
    private Double memoryMb;

    @Column(length = 64)
    private String language;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "submitted_at", nullable = false)
    private OffsetDateTime submittedAt = OffsetDateTime.now();
}
