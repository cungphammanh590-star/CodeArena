package com.codearena.business.coach.memory.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoachSessionRepository extends JpaRepository<CoachSessionEntity, String> {

    Optional<CoachSessionEntity> findBySessionIdAndUserId(String sessionId, Long userId);

    Optional<CoachSessionEntity> findFirstByUserIdAndSubmissionIdAndStatusOrderByUpdatedAtDesc(
            Long userId, String submissionId, String status);

    Optional<CoachSessionEntity> findFirstByUserIdAndProblemIdAndStatusOrderByUpdatedAtDesc(
            Long userId, Integer problemId, String status);

    Optional<CoachSessionEntity> findFirstByUserIdAndTopicAndStatusOrderByUpdatedAtDesc(
            Long userId, String topic, String status);

    List<CoachSessionEntity> findTop20ByUserIdOrderByUpdatedAtDesc(Long userId);
}
