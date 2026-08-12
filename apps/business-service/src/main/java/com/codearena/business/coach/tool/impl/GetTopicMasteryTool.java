package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.learning.mastery.domain.UserProblemFlagRepository;
import com.codearena.business.problem.domain.ProblemEntity;
import com.codearena.business.problem.domain.ProblemRepository;
import com.codearena.business.submission.domain.SubmissionEntity;
import com.codearena.business.submission.domain.SubmissionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 按标签聚合**当前用户**的掌握与挣扎情况（非全局 problem_stats）。 */
@Component
@RequiredArgsConstructor
public class GetTopicMasteryTool implements CoachTool {
    private final SubmissionRepository submissionRepository;
    private final ProblemRepository problemRepository;
    private final UserProblemFlagRepository userProblemFlagRepository;

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
        return "按标签/知识点聚合当前用户的掌握与挣扎情况。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        String topic = context.paramString("topic");
        if (topic == null || topic.isBlank()) {
            return CoachToolResult.failure("topic required");
        }
        Long userId = context.userId();
        if (userId == null) {
            return CoachToolResult.failure("user required");
        }

        String needle = topic.trim().toLowerCase();
        Map<Integer, Agg> byProblem = new LinkedHashMap<>();
        List<SubmissionEntity> subs =
                submissionRepository.findByUserIdAndSubmittedAtGreaterThanEqualOrderBySubmittedAtDesc(
                        userId, java.time.OffsetDateTime.parse("2000-01-01T00:00:00Z"));
        for (SubmissionEntity s : subs) {
            Agg a = byProblem.computeIfAbsent(s.getProblemId(), id -> new Agg());
            a.totalAttempts += 1;
            if ("Accepted".equals(s.getStatus())) {
                a.acceptedCount += 1;
            } else {
                a.wrongCount += 1;
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, Agg> e : byProblem.entrySet()) {
            Integer problemId = e.getKey();
            ProblemEntity problem = problemRepository.findByProblemId(problemId).orElse(null);
            String tags = problem == null || problem.getTags() == null
                    ? ""
                    : problem.getTags().toLowerCase();
            if (!tags.contains(needle)) {
                continue;
            }
            Agg a = e.getValue();
            boolean mastered = userProblemFlagRepository
                    .findByUserIdAndProblemId(userId, problemId)
                    .map(f -> Boolean.TRUE.equals(f.getMastered()))
                    .orElse(false);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("problem_id", problemId);
            item.put("title", problem != null ? problem.getTitle() : ("Problem " + problemId));
            item.put("difficulty", problem != null ? problem.getDifficulty() : null);
            item.put("total_attempts", a.totalAttempts);
            item.put("accepted_count", a.acceptedCount);
            item.put("wrong_count", a.wrongCount);
            item.put(
                    "acceptance_rate",
                    a.totalAttempts == 0 ? 0.0 : round(100.0 * a.acceptedCount / a.totalAttempts));
            item.put(
                    "struggle_score",
                    a.totalAttempts == 0 ? 0.0 : (double) a.wrongCount / a.totalAttempts);
            item.put("mastered", mastered);
            rows.add(item);
            if (rows.size() >= 20) {
                break;
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topic", topic);
        data.put("user_id", userId);
        data.put("problems", rows);
        data.put("matched", rows.size());
        return CoachToolResult.success(data);
    }

    private static final class Agg {
        int totalAttempts;
        int acceptedCount;
        int wrongCount;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
