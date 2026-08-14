package com.codearena.business.knowledge.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbDocumentRepository extends JpaRepository<KbDocumentEntity, Long> {
    List<KbDocumentEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<KbDocumentEntity> findByIdAndUserId(Long id, Long userId);

    List<KbDocumentEntity> findByStatusIn(List<String> statuses);
}
