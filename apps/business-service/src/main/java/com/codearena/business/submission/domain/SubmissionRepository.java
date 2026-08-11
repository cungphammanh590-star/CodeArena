package com.codearena.business.submission.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionRepository extends JpaRepository<SubmissionEntity, Long> {
    Optional<SubmissionEntity> findBySubmissionId(String submissionId);

    List<SubmissionEntity> findTop80ByProblemIdOrderBySubmittedAtDesc(Integer problemId);

    Optional<SubmissionEntity> findFirstByProblemIdAndUserIdOrderBySubmittedAtDesc(
            Integer problemId, Long userId);

    Optional<SubmissionEntity> findFirstByProblemIdOrderBySubmittedAtDesc(Integer problemId);

    long countByStatus(String status);

    long countByUserId(Long userId);

    long countByUserIdAndStatus(Long userId, String status);

    List<SubmissionEntity> findTop80ByUserIdOrderBySubmittedAtDesc(Long userId);

    List<SubmissionEntity> findByUserIdAndSubmittedAtGreaterThanEqualAndSubmittedAtLessThanOrderBySubmittedAtDesc(
            Long userId, OffsetDateTime startInclusive, OffsetDateTime endExclusive);

    List<SubmissionEntity> findByUserIdAndSubmittedAtGreaterThanEqualOrderBySubmittedAtDesc(
            Long userId, OffsetDateTime startInclusive);
}
