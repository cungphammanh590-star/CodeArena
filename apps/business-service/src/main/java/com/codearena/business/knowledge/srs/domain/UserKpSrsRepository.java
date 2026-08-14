package com.codearena.business.knowledge.srs.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserKpSrsRepository extends JpaRepository<UserKpSrsEntity, UserKpSrsEntity.Pk> {

    Optional<UserKpSrsEntity> findByUserIdAndKnowledgePointId(Long userId, Long knowledgePointId);

    List<UserKpSrsEntity> findByUserId(Long userId);

    void deleteByUserIdAndKnowledgePointId(Long userId, Long knowledgePointId);

    void deleteByKnowledgePointId(Long knowledgePointId);

    @Query(
            """
            SELECT s FROM UserKpSrsEntity s
            WHERE s.userId = :userId AND s.suspended = FALSE AND s.dueAt <= :until
            ORDER BY s.dueAt ASC
            """)
    List<UserKpSrsEntity> findDue(@Param("userId") Long userId, @Param("until") OffsetDateTime until);

    @Query(
            """
            SELECT COUNT(s) FROM UserKpSrsEntity s
            WHERE s.userId = :userId AND s.suspended = FALSE AND s.dueAt <= :until
            """)
    long countDue(@Param("userId") Long userId, @Param("until") OffsetDateTime until);
}
