package com.codearena.business.learning.plan.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanDailyTaskRepository extends JpaRepository<PlanDailyTaskEntity, Long> {

    List<PlanDailyTaskEntity> findByPlanIdOrderByDayNumAsc(Long planId);

    Optional<PlanDailyTaskEntity> findFirstByPlanIdAndScheduledDate(Long planId, LocalDate scheduledDate);
}
