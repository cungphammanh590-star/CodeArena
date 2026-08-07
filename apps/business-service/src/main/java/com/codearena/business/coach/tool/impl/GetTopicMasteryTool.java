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
import com.codearena.business.problem.domain.ProblemStatsEntity;
import com.codearena.business.problem.domain.ProblemStatsRepository;

@Component
@RequiredArgsConstructor
public class GetTopicMasteryTool implements CoachTool {
    private final ProblemStatsRepository problemStatsRepository;

    @Override
    public String name() {
        return "get_topic_mastery";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "按标签/知识点聚合掌握与挣扎情况。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        String topic = context.paramString("topic");
        if (topic == null || topic.isBlank()) {
            return CoachToolResult.failure("topic required");
        }
        String needle = topic.trim().toLowerCase();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProblemStatsEntity s : problemStatsRepository.findAll()) {
            String tags = s.getTopicTags() == null ? "" : s.getTopicTags().toLowerCase();
            if (!tags.contains(needle)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("problem_id", s.getProblemId());
            item.put("title", s.getTitle());
            item.put("struggle_score", s.getStruggleScore());
            item.put("acceptance_rate", s.getAcceptanceRate());
            rows.add(item);
            if (rows.size() >= 20) {
                break;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topic", topic);
        data.put("problems", rows);
        return CoachToolResult.success(data);
    }
}
