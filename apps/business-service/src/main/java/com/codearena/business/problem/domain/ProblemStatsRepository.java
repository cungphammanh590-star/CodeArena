package com.codearena.business.problem.domain;

import com.codearena.business.problem.domain.ProblemStatsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemStatsRepository extends JpaRepository<ProblemStatsEntity, Integer> {}
