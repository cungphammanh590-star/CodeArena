package com.codearena.business.learning.plan.service;

import com.codearena.business.problem.domain.ProblemStatsEntity;
import com.codearena.business.problem.domain.ProblemStatsRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TopicPoolResolver implements GoalPoolResolver {

    private final BankBackedPoolResolver bank;
    private final ProblemStatsRepository problemStatsRepository;

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
        List<PoolItem> fromStats = new ArrayList<>();
        int order = 0;
        for (ProblemStatsEntity s : problemStatsRepository.findAll()) {
            String tags = s.getTopicTags() == null ? "" : s.getTopicTags().toLowerCase(Locale.ROOT);
            if (!tags.contains(needle)) {
                continue;
            }
            if (difficulty != null
                    && !difficulty.isBlank()
                    && !"mixed".equalsIgnoreCase(difficulty)
                    && s.getDifficulty() != null
                    && !s.getDifficulty().equalsIgnoreCase(difficulty.trim())) {
                continue;
            }
            fromStats.add(new PoolItem(
                    s.getProblemId(),
                    s.getTitle() == null ? ("Problem " + s.getProblemId()) : s.getTitle(),
                    s.getTitleSlug() == null ? String.valueOf(s.getProblemId()) : s.getTitleSlug(),
                    s.getDifficulty() == null ? "Medium" : s.getDifficulty(),
                    null,
                    order++));
            if (fromStats.size() >= limit) {
                break;
            }
        }
        return fromStats;
    }
}
