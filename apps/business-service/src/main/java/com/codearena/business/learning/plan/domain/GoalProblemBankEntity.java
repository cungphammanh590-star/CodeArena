package com.codearena.business.learning.plan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "goal_problem_banks")
@IdClass(GoalProblemBankEntity.Pk.class)
public class GoalProblemBankEntity {

    @Id
    @Column(name = "goal_type", length = 32)
    private String goalType;

    @Id
    @Column(name = "goal_ref", length = 128)
    private String goalRef;

    @Id
    @Column(name = "problem_id")
    private Integer problemId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String slug;

    @Column(nullable = false, length = 16)
    private String difficulty = "Medium";

    @Column(name = "stage_hint", length = 32)
    private String stageHint;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Getter
    @Setter
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private String goalType;
        private String goalRef;
        private Integer problemId;
    }
}
