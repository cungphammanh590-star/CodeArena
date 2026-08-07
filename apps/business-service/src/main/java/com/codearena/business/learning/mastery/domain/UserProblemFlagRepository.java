package com.codearena.business.learning.mastery.domain;

import com.codearena.business.learning.mastery.domain.UserProblemFlagEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProblemFlagRepository
        extends JpaRepository<UserProblemFlagEntity, UserProblemFlagEntity.Pk> {

    List<UserProblemFlagEntity> findByUserIdAndMasteredTrue(Long userId);

    Optional<UserProblemFlagEntity> findByUserIdAndProblemId(Long userId, Integer problemId);
}
