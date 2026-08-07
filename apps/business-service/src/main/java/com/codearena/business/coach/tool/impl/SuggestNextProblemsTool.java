package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.codearena.business.problem.domain.ProblemStatsRepository;

@Component
@RequiredArgsConstructor
public class SuggestNextProblemsTool implements CoachTool {
    private final ProblemStatsRepository problemStatsRepository;
    private final ListUnpassedProblemsTool unpassed;

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
        return "选题：有未通过则返回续刷候选，否则返回规则引擎新题候选。禁止推荐列表外题号。";
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
        List<Map<String, Object>> candidates = problemStatsRepository.findAll().stream()
                .filter(s -> s.getAcceptedCount() == null || s.getAcceptedCount() == 0)
                .limit(limit)
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("problem_id", s.getProblemId());
                    m.put("title", s.getTitle());
                    m.put("difficulty", s.getDifficulty());
                    return m;
                })
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "recommend_new");
        data.put("candidates", candidates);
        data.put("note", "规则引擎候选；禁止推荐列表外题号");
        return CoachToolResult.success(data);
    }
}
