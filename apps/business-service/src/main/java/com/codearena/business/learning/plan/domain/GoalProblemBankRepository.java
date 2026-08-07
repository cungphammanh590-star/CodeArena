package com.codearena.business.learning.plan.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalProblemBankRepository
        extends JpaRepository<GoalProblemBankEntity, GoalProblemBankEntity.Pk> {

    List<GoalProblemBankEntity> findByGoalTypeAndGoalRefOrderBySortOrderAsc(
            String goalType, String goalRef);
}
