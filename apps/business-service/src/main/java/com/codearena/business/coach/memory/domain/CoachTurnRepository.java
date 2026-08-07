package com.codearena.business.coach.memory.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoachTurnRepository extends JpaRepository<CoachTurnEntity, Long> {

    List<CoachTurnEntity> findBySessionIdAndUserIdOrderByCreatedAtAsc(String sessionId, Long userId);

    List<CoachTurnEntity> findTop50BySessionIdAndUserIdOrderByCreatedAtDesc(String sessionId, Long userId);
}
