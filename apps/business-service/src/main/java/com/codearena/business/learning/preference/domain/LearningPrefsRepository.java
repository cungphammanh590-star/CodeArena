package com.codearena.business.learning.preference.domain;

import com.codearena.business.learning.preference.domain.LearningPrefsEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningPrefsRepository extends JpaRepository<LearningPrefsEntity, Long> {
    Optional<LearningPrefsEntity> findFirstByOrderByIdAsc();

    Optional<LearningPrefsEntity> findFirstByUserIdOrderByIdAsc(Long userId);
}
