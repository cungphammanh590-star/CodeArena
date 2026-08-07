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
import com.codearena.business.learning.mastery.domain.UserProblemFlagEntity;
import com.codearena.business.learning.mastery.domain.UserProblemFlagRepository;
import com.codearena.business.problem.domain.ProblemStatsRepository;

@Component
@RequiredArgsConstructor
public class GetProblemMasteryTool implements CoachTool {
    private final ProblemStatsRepository problemStatsRepository;
    private final UserProblemFlagRepository flagRepository;

    @Override
    public String name() {
        return "get_problem_mastery";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "单题掌握/挣扎统计；可省略 problem_id 则用当前绑定题。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        Integer pid = context.paramInt("problem_id");
        if (pid == null) {
            pid = context.problemId();
        }
        if (pid == null || pid <= 0) {
            return CoachToolResult.failure("problem_id required");
        }
        Integer finalPid = pid;
        boolean mastered = flagRepository
                .findByUserIdAndProblemId(context.userId(), finalPid)
                .map(UserProblemFlagEntity::getMastered)
                .orElse(false);
        return problemStatsRepository
                .findById(finalPid)
                .map(row -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("problem_id", finalPid);
                    data.put("title", row.getTitle());
                    data.put("mastered", mastered);
                    data.put("struggle_score", row.getStruggleScore());
                    data.put("total_attempts", row.getTotalAttempts());
                    data.put("accepted_count", row.getAcceptedCount());
                    data.put("last_status", row.getLastStatus());
                    return CoachToolResult.success(data);
                })
                .orElseGet(() -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("problem_id", finalPid);
                    data.put("mastered", mastered);
                    data.put("note", "无 stats 行");
                    return CoachToolResult.success(data);
                });
    }
}
