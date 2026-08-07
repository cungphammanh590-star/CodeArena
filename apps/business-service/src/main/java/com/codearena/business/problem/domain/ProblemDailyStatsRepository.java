package com.codearena.business.problem.domain;

import com.codearena.business.problem.domain.ProblemDailyStatsEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemDailyStatsRepository
        extends JpaRepository<ProblemDailyStatsEntity, ProblemDailyStatsEntity.Pk> {

    List<ProblemDailyStatsEntity> findTop90ByProblemIdOrderByDayDesc(Integer problemId);
}
