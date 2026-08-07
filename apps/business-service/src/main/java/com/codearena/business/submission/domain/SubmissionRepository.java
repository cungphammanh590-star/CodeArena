package com.codearena.business.submission.domain;

import com.codearena.business.submission.domain.SubmissionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionRepository extends JpaRepository<SubmissionEntity, Long> {
    Optional<SubmissionEntity> findBySubmissionId(String submissionId);

    List<SubmissionEntity> findTop80ByProblemIdOrderBySubmittedAtDesc(Integer problemId);

    long countByStatus(String status);
}
