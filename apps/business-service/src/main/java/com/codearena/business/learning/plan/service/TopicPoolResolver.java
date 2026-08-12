package com.codearena.business.learning.plan.service;

import com.codearena.business.problem.domain.ProblemEntity;
import com.codearena.business.problem.domain.ProblemRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TopicPoolResolver implements GoalPoolResolver {

    private final BankBackedPoolResolver bank;
    private final ProblemRepository problemRepository;

    @Override
    public String goalType() {
        return "topic";
    }

    @Override
    public List<PoolItem> resolve(String goalRef, String difficulty, int limit) {
        String ref = BankBackedPoolResolver.normalizeTopic(goalRef);
        List<PoolItem> fromBank = bank.fromBank("topic", ref, difficulty, limit);
        if (!fromBank.isEmpty()) {
            return fromBank;
        }
        String needle = ref.toLowerCase(Locale.ROOT);
        List<PoolItem> fromProblems = new ArrayList<>();
        int order = 0;
        for (ProblemEntity p : problemRepository.findAll()) {
            String tags = p.getTags() == null ? "" : p.getTags().toLowerCase(Locale.ROOT);
            if (!tags.contains(needle)) {
                continue;
            }
            if (difficulty != null
                    && !difficulty.isBlank()
                    && !"mixed".equalsIgnoreCase(difficulty)
                    && p.getDifficulty() != null
                    && !p.getDifficulty().equalsIgnoreCase(difficulty.trim())) {
                continue;
            }
            fromProblems.add(new PoolItem(
                    p.getProblemId(),
                    p.getTitle() == null ? ("Problem " + p.getProblemId()) : p.getTitle(),
                    p.getSlug() == null ? String.valueOf(p.getProblemId()) : p.getSlug(),
                    p.getDifficulty() == null ? "Medium" : p.getDifficulty(),
                    null,
                    order++));
            if (fromProblems.size() >= limit) {
                break;
            }
        }
        return fromProblems;
    }
}
