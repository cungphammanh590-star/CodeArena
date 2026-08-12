package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.learning.mastery.domain.UserProblemFlagEntity;
import com.codearena.business.learning.mastery.domain.UserProblemFlagRepository;
import com.codearena.business.learning.srs.SrsService;
import com.codearena.business.problem.domain.ProblemEntity;
import com.codearena.business.problem.domain.ProblemRepository;
import com.codearena.business.submission.domain.SubmissionEntity;
import com.codearena.business.submission.domain.SubmissionRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GetProblemMasteryTool implements CoachTool {
    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;
    private final UserProblemFlagRepository flagRepository;
    private final SrsService srsService;

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
        return "单题掌握/挣扎统计（当前用户）；可省略 problem_id 则用当前绑定题。";
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
        Long userId = context.userId();
        if (userId == null) {
            return CoachToolResult.failure("user required");
        }

        boolean mastered = flagRepository
                .findByUserIdAndProblemId(userId, pid)
                .map(UserProblemFlagEntity::getMastered)
                .orElse(false);

        List<SubmissionEntity> subs =
                submissionRepository.findTop80ByProblemIdOrderBySubmittedAtDesc(pid).stream()
                        .filter(s -> userId.equals(s.getUserId()))
                        .toList();
        long accepted = subs.stream().filter(s -> "Accepted".equals(s.getStatus())).count();
        long wrong = subs.size() - accepted;
        ProblemEntity problem = problemRepository.findByProblemId(pid).orElse(null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("problem_id", pid);
        data.put("title", problem != null ? problem.getTitle() : ("Problem " + pid));
        data.put("mastered", mastered);
        data.put("total_attempts", subs.size());
        data.put("accepted_count", accepted);
        data.put("wrong_count", wrong);
        data.put(
                "struggle_score",
                subs.isEmpty() ? 0.0 : (double) wrong / subs.size());
        data.put("last_status", subs.isEmpty() ? null : subs.get(0).getStatus());
        data.putAll(srsService.cardDigest(userId, pid));
        return CoachToolResult.success(data);
    }
}
