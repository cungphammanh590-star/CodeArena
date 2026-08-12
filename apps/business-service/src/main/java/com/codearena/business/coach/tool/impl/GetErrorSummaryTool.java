package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.problem.domain.ProblemEntity;
import com.codearena.business.problem.domain.ProblemRepository;
import com.codearena.business.submission.domain.SubmissionEntity;
import com.codearena.business.submission.domain.SubmissionRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GetErrorSummaryTool implements CoachTool {
    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;

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
        return "读取已绑定题目对当前用户的错因分布、挣扎指数与标签等统计要点。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        Integer pid = context.problemId();
        if (pid == null || pid <= 0) {
            return CoachToolResult.failure("尚未绑定题目");
        }
        Long userId = context.userId();
        if (userId == null) {
            return CoachToolResult.failure("user required");
        }

        ProblemEntity problem = problemRepository.findByProblemId(pid).orElse(null);
        List<SubmissionEntity> subs =
                submissionRepository.findTop80ByProblemIdOrderBySubmittedAtDesc(pid).stream()
                        .filter(s -> userId.equals(s.getUserId()))
                        .toList();
        if (subs.isEmpty() && problem == null) {
            return CoachToolResult.failure("无该题统计");
        }

        long accepted = subs.stream().filter(s -> "Accepted".equals(s.getStatus())).count();
        long wrong = subs.size() - accepted;
        Map<String, Long> breakdown = subs.stream()
                .collect(Collectors.groupingBy(
                        s -> Objects.toString(s.getStatus(), "Unknown"), Collectors.counting()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("problem_id", pid);
        data.put("title", problem != null ? problem.getTitle() : ("Problem " + pid));
        data.put("difficulty", problem != null ? problem.getDifficulty() : null);
        data.put("topic_tags", problem != null ? problem.getTags() : null);
        data.put("total_attempts", subs.size());
        data.put("accepted_count", accepted);
        data.put("wrong_count", wrong);
        data.put(
                "struggle_score",
                subs.isEmpty() ? 0.0 : (double) wrong / subs.size());
        data.put("status_breakdown", breakdown);
        data.put("last_status", subs.isEmpty() ? null : subs.get(0).getStatus());
        return CoachToolResult.success(data);
    }
}
