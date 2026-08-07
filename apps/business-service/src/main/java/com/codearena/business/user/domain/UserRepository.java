package com.codearena.business.user.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByUsername(String username);

    Optional<UserEntity> findByPublicId(String publicId);

    Optional<UserEntity> findByEmail(String email);

    boolean existsByUsername(String username);
}
