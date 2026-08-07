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
public class GetErrorSummaryTool implements CoachTool {
    private final ProblemStatsRepository problemStatsRepository;

    @Override
    public String name() {
        return "get_error_summary";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "读取已绑定题目的错因分布、挣扎指数与标签等统计要点。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        Integer pid = context.problemId();
        if (pid == null || pid <= 0) {
            return CoachToolResult.failure("尚未绑定题目");
        }
        return problemStatsRepository
                .findById(pid)
                .map(row -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("problem_id", pid);
                    data.put("title", row.getTitle());
                    data.put("difficulty", row.getDifficulty());
                    data.put("topic_tags", row.getTopicTags());
                    data.put("total_attempts", row.getTotalAttempts());
                    data.put("accepted_count", row.getAcceptedCount());
                    data.put("wrong_count", row.getWrongCount());
                    data.put("struggle_score", row.getStruggleScore());
                    data.put("status_breakdown", row.getStatusBreakdown());
                    data.put("last_status", row.getLastStatus());
                    return CoachToolResult.success(data);
                })
                .orElseGet(() -> CoachToolResult.failure("无该题统计"));
    }
}
