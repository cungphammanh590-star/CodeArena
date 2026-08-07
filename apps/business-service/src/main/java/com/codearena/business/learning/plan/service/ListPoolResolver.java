package com.codearena.business.learning.plan.service;

import com.codearena.business.learning.list.domain.ProblemListItemEntity;
import com.codearena.business.learning.list.domain.ProblemListItemRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ListPoolResolver implements GoalPoolResolver {

    private final ProblemListItemRepository listItemRepository;
    private final BankBackedPoolResolver bank;

    @Override
    public String goalType() {
        return "list";
    }

    @Override
    public List<PoolItem> resolve(String goalRef, String difficulty, int limit) {
        String ref = goalRef == null || goalRef.isBlank() ? "hot100" : goalRef.trim();
        List<ProblemListItemEntity> items = listItemRepository.findByListIdOrderBySortOrderAsc(ref);
        List<PoolItem> out = new ArrayList<>();
        for (ProblemListItemEntity item : items) {
            if (difficulty != null
                    && !difficulty.isBlank()
                    && !"mixed".equalsIgnoreCase(difficulty)
                    && item.getDifficulty() != null
                    && !item.getDifficulty().equalsIgnoreCase(difficulty.trim())) {
                continue;
            }
            out.add(new PoolItem(
                    item.getProblemId(),
                    item.getTitle(),
                    item.getSlug(),
                    item.getDifficulty(),
                    null,
                    item.getSortOrder() == null ? 0 : item.getSortOrder()));
            if (out.size() >= limit) {
                break;
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        return bank.fromBank("list", ref, difficulty, limit);
    }
}
