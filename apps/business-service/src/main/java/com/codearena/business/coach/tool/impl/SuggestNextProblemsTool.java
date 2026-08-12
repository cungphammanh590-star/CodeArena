package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.learning.list.domain.ProblemListItemEntity;
import com.codearena.business.learning.list.domain.ProblemListItemRepository;
import com.codearena.business.learning.preference.service.LearningPrefsService;
import com.codearena.business.problem.domain.ProblemEntity;
import com.codearena.business.problem.domain.ProblemRepository;
import com.codearena.business.submission.domain.SubmissionRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SuggestNextProblemsTool implements CoachTool {
    private final ListUnpassedProblemsTool unpassed;
    private final LearningPrefsService learningPrefsService;
    private final ProblemListItemRepository problemListItemRepository;
    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;

    @Override
    public String name() {
        return "suggest_next_problems";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "选题：有未通过则返回续刷候选，否则从活跃题单推荐未 AC 题。禁止推荐列表外题号。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        int limit = 3;
        Integer lim = context.paramInt("limit");
        if (lim != null) {
            limit = Math.max(1, Math.min(5, lim));
        }
        CoachToolResult unpassedResult = unpassed.execute(context);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = unpassedResult.data() != null
                        && unpassedResult.data().get("items") instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();
        if (!items.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("mode", "continue_unpassed");
            data.put("candidates", items.stream().limit(limit).toList());
            data.put("note", "规则：优先未通过题；禁止列表外题号");
            return CoachToolResult.success(data);
        }

        Long userId = context.userId();
        String listId = "hot100";
        if (userId != null) {
            listId = String.valueOf(
                    learningPrefsService.toLearningMap(learningPrefsService.getOrCreate(userId))
                            .get("active_list_id"));
        }
        Set<Integer> accepted = userId == null
                ? Set.of()
                : new HashSet<>(
                        submissionRepository.findDistinctProblemIdsByUserIdAndStatus(
                                userId, "Accepted"));

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (ProblemListItemEntity item :
                problemListItemRepository.findByListIdOrderBySortOrderAsc(listId)) {
            if (accepted.contains(item.getProblemId())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("problem_id", item.getProblemId());
            m.put("title", item.getTitle());
            m.put("difficulty", item.getDifficulty());
            candidates.add(m);
            if (candidates.size() >= limit) {
                break;
            }
        }
        if (candidates.isEmpty()) {
            for (ProblemEntity p : problemRepository.findAll()) {
                if (accepted.contains(p.getProblemId())) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("problem_id", p.getProblemId());
                m.put("title", p.getTitle());
                m.put("difficulty", p.getDifficulty());
                candidates.add(m);
                if (candidates.size() >= limit) {
                    break;
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "recommend_new");
        data.put("candidates", candidates);
        data.put("active_list_id", listId);
        data.put("note", "活跃题单未 AC 候选；禁止推荐列表外题号");
        return CoachToolResult.success(data);
    }
}
