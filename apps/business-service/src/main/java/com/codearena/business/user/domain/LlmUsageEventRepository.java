package com.codearena.business.user.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LlmUsageEventRepository extends JpaRepository<LlmUsageEventEntity, Long> {

    List<LlmUsageEventEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByUserIdAndCreatedAtGreaterThanEqual(Long userId, Instant since);

    @Query(
            """
            select coalesce(sum(e.totalTokens), 0)
            from LlmUsageEventEntity e
            where e.userId = :userId and e.createdAt >= :since
            """)
    Long sumTokensSince(@Param("userId") Long userId, @Param("since") Instant since);
}
