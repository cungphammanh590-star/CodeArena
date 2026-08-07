package com.codearena.business.user.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentityEntity, Long> {
    Optional<UserIdentityEntity> findByProviderAndProviderUid(String provider, String providerUid);
}
