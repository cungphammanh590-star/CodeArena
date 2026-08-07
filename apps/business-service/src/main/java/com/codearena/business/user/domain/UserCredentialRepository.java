package com.codearena.business.user.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCredentialRepository extends JpaRepository<UserCredentialEntity, Long> {}
