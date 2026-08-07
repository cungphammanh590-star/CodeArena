package com.codearena.business.learning.list.domain;

import com.codearena.business.learning.list.domain.ProblemListItemEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemListItemRepository
        extends JpaRepository<ProblemListItemEntity, ProblemListItemEntity.Pk> {

    List<ProblemListItemEntity> findByListIdOrderBySortOrderAsc(String listId);

    void deleteByListIdAndProblemId(String listId, Integer problemId);

    long countByListId(String listId);
}
