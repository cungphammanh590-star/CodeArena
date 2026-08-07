package com.codearena.business.learning.plan.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudyPlanRepository extends JpaRepository<StudyPlanEntity, Long> {

    Optional<StudyPlanEntity> findFirstByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);

    List<StudyPlanEntity> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);
}
