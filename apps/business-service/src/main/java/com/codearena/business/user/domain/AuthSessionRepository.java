package com.codearena.business.user.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthSessionRepository extends JpaRepository<AuthSessionEntity, Long> {
    Optional<AuthSessionEntity> findByTokenHashAndRevokedAtIsNull(String tokenHash);
}
