package com.codearena.business.knowledge.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbEmbeddingRepository extends JpaRepository<KbEmbeddingEntity, Long> {
    List<KbEmbeddingEntity> findByKnowledgePointIdAndStatus(Long knowledgePointId, String status);

    List<KbEmbeddingEntity> findByKnowledgePointIdInAndStatus(List<Long> knowledgePointIds, String status);

    Optional<KbEmbeddingEntity> findFirstByKnowledgePointIdAndStatusOrderByCreatedAtDesc(
            Long knowledgePointId, String status);
}
