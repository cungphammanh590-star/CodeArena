package com.codearena.business.coach.memory.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCoachMemoryRepository extends JpaRepository<UserCoachMemoryEntity, Long> {

    List<UserCoachMemoryEntity> findByUserIdAndActiveTrueOrderByUpdatedAtDesc(Long userId);

    List<UserCoachMemoryEntity> findByUserIdAndKindAndActiveTrueOrderByUpdatedAtDesc(
            Long userId, String kind);

    Optional<UserCoachMemoryEntity> findByIdAndUserId(Long id, Long userId);
}
