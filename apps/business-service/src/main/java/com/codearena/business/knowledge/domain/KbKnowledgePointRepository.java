package com.codearena.business.knowledge.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbKnowledgePointRepository extends JpaRepository<KbKnowledgePointEntity, Long> {
    List<KbKnowledgePointEntity> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);

    List<KbKnowledgePointEntity> findByDocumentIdAndStatus(Long documentId, String status);

    List<KbKnowledgePointEntity> findByDocumentId(Long documentId);

    Optional<KbKnowledgePointEntity> findByIdAndUserIdAndStatus(Long id, Long userId, String status);

    Optional<KbKnowledgePointEntity> findByIdAndUserId(Long id, Long userId);
}
