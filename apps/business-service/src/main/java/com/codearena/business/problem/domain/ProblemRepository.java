package com.codearena.business.problem.domain;

import com.codearena.business.problem.domain.ProblemEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemRepository extends JpaRepository<ProblemEntity, Long> {
    Optional<ProblemEntity> findByProblemId(Integer problemId);
}
