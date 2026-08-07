package com.codearena.business.learning.list.domain;

import com.codearena.business.learning.list.domain.ProblemListEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemListRepository extends JpaRepository<ProblemListEntity, String> {}
