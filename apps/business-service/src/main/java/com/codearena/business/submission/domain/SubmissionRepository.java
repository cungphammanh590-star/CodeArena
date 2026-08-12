package com.codearena.business.submission.domain;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Query(
            """
            SELECT DISTINCT s.problemId FROM SubmissionEntity s
            WHERE s.userId = :userId AND s.status = :status
              AND s.problemId IN :problemIds
            """)
    List<Integer> findDistinctProblemIdsByUserIdAndStatusAndProblemIdIn(
            @Param("userId") Long userId,
            @Param("status") String status,
            @Param("problemIds") Collection<Integer> problemIds);

    @Query(
            """
            SELECT DISTINCT s.problemId FROM SubmissionEntity s
            WHERE s.userId = :userId AND s.status = :status
            """)
    List<Integer> findDistinctProblemIdsByUserIdAndStatus(
            @Param("userId") Long userId, @Param("status") String status);

    @Query(
            """
            SELECT s.problemId, MAX(s.submittedAt) FROM SubmissionEntity s
            WHERE s.userId = :userId AND s.status = 'Accepted'
            GROUP BY s.problemId
            """)
    List<Object[]> findLastAcceptedAtByUser(@Param("userId") Long userId);
}
