package com.codearena.business.learning.srs.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserProblemSrsRepository
        extends JpaRepository<UserProblemSrsEntity, UserProblemSrsEntity.Pk> {

    Optional<UserProblemSrsEntity> findByUserIdAndProblemId(Long userId, Integer problemId);

    List<UserProblemSrsEntity> findByUserId(Long userId);

    long countByUserId(Long userId);

    @Query(
            """
            SELECT s FROM UserProblemSrsEntity s
            WHERE s.userId = :userId
              AND s.suspended = FALSE
              AND s.dueAt <= :until
            ORDER BY s.dueAt ASC
            """)
    List<UserProblemSrsEntity> findDue(
            @Param("userId") Long userId, @Param("until") OffsetDateTime until);

    @Query(
            """
            SELECT COUNT(s) FROM UserProblemSrsEntity s
            WHERE s.userId = :userId
              AND s.suspended = FALSE
              AND s.dueAt <= :until
            """)
    long countDue(@Param("userId") Long userId, @Param("until") OffsetDateTime until);
}
