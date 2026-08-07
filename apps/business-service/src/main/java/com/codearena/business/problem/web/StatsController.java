package com.codearena.business.problem.web;

import com.codearena.business.problem.domain.ProblemDailyStatsEntity;
import com.codearena.business.problem.domain.ProblemStatsEntity;
import com.codearena.business.submission.domain.SubmissionEntity;
import com.codearena.business.problem.domain.ProblemDailyStatsRepository;
import com.codearena.business.problem.domain.ProblemStatsRepository;
import com.codearena.business.submission.domain.SubmissionRepository;
import com.codearena.business.learning.mastery.domain.UserProblemFlagRepository;
import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class StatsController {

    private final SubmissionRepository submissionRepository;
    private final ProblemStatsRepository problemStatsRepository;
    private final ProblemDailyStatsRepository problemDailyStatsRepository;
    private final UserProblemFlagRepository userProblemFlagRepository;
    private final CurrentUserService currentUserService;

    @GetMapping("/api/stats")
    public ResponseEntity<?> stats(@RequestParam(value = "date", required = false) String date) {
        LocalDate day = LocalDate.now();
        if (date != null && !date.isBlank()) {
            try {
                day = LocalDate.parse(date.trim());
            } catch (DateTimeParseException ex) {
                return ResponseEntity.badRequest()
                        .body(Map.of("status", "error", "message", "无效日期，请使用 YYYY-MM-DD"));
            }
        }

        long total = submissionRepository.count();
        long accepted = submissionRepository.countByStatus("Accepted");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", day.toString());
        body.put("total_submissions", total);
        body.put("accepted_count", accepted);
        body.put("acceptance_rate", total == 0 ? 0.0 : round(100.0 * accepted / total));
        body.put("easy_solved", 0);
        body.put("medium_solved", 0);
        body.put("hard_solved", 0);
        body.put("today_submissions", 0);
        body.put("today_accepted", 0);
        body.put("today_acceptance_rate", 0.0);
        body.put("streak_days", 0);
        body.put("recent", List.of());
        body.put("today_items", List.of());
        body.put("today_wrong", List.of());
        body.put("last7", emptyLast7(day));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/problems")
    public Map<String, Object> problems() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProblemStatsEntity row : problemStatsRepository.findAll()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("problem_id", row.getProblemId());
            item.put("title", row.getTitle());
            item.put("title_slug", row.getTitleSlug());
            item.put("difficulty", row.getDifficulty());
            item.put("topic_tags", row.getTopicTags());
            item.put("total_attempts", row.getTotalAttempts());
            item.put("accepted_count", row.getAcceptedCount());
            item.put("wrong_count", row.getWrongCount());
            item.put("acceptance_rate", row.getAcceptanceRate());
            item.put("last_status", row.getLastStatus());
            items.add(item);
        }
        return Map.of("problems", items);
    }

    @GetMapping("/api/problems/{problemId}/stats")
    public ResponseEntity<?> problemStats(
            HttpServletRequest request, @PathVariable Integer problemId) {
        UserEntity user = currentUserService.require(request);
        return problemStatsRepository
                .findById(problemId)
                .map(row -> {
                    Map<String, Object> problem = new LinkedHashMap<>();
                    problem.put("problem_id", row.getProblemId());
                    problem.put("title", row.getTitle());
                    problem.put("title_slug", row.getTitleSlug());
                    problem.put("difficulty", row.getDifficulty());
                    problem.put("topic_tags", row.getTopicTags());
                    problem.put("total_attempts", row.getTotalAttempts());
                    problem.put("accepted_count", row.getAcceptedCount());
                    problem.put("wrong_count", row.getWrongCount());
                    problem.put("status_breakdown", row.getStatusBreakdown());
                    problem.put("acceptance_rate", row.getAcceptanceRate());
                    problem.put("struggle_score", row.getStruggleScore());
                    problem.put("avg_attempts_to_ac", row.getAvgAttemptsToAc());
                    problem.put("last_status", row.getLastStatus());
                    problem.put(
                            "mastered",
                            userProblemFlagRepository
                                    .findByUserIdAndProblemId(user.getId(), problemId)
                                    .map(f -> Boolean.TRUE.equals(f.getMastered()))
                                    .orElse(false));

                    List<Map<String, Object>> daily = new ArrayList<>();
                    for (ProblemDailyStatsEntity d :
                            problemDailyStatsRepository.findTop90ByProblemIdOrderByDayDesc(problemId)) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("day", d.getDay().toString());
                        item.put("attempts", d.getAttempts());
                        item.put("accepted_today", d.getAcceptedToday());
                        item.put("wrong_today", d.getWrongToday());
                        item.put("status_change", d.getStatusChange());
                        daily.add(item);
                    }

                    List<Map<String, Object>> submissions = new ArrayList<>();
                    for (SubmissionEntity s :
                            submissionRepository.findTop80ByProblemIdOrderBySubmittedAtDesc(problemId)) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("submission_id", s.getSubmissionId());
                        item.put("status", s.getStatus());
                        item.put("language", s.getLanguage());
                        item.put("runtime_ms", s.getRuntimeMs());
                        item.put("memory_mb", s.getMemoryMb());
                        item.put(
                                "submitted_at",
                                s.getSubmittedAt() == null ? null : s.getSubmittedAt().toString());
                        submissions.add(item);
                    }

                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("problem", problem);
                    body.put("daily", daily);
                    body.put("submissions", submissions);
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("status", "error", "message", "not found")));
    }

    @GetMapping("/api/problems/{problemId}/llm-context")
    public Map<String, Object> llmContext(@PathVariable Integer problemId) {
        String markdown = problemStatsRepository
                .findById(problemId)
                .map(row -> "# Problem " + problemId + "\n\n"
                        + "Title: " + nullToEmpty(row.getTitle()) + "\n"
                        + "Difficulty: " + nullToEmpty(row.getDifficulty()) + "\n"
                        + "Attempts: " + row.getTotalAttempts() + "\n"
                        + "Accepted: " + row.getAcceptedCount() + "\n")
                .orElse("# Problem " + problemId + "\n\n(no stats yet)\n");
        return Map.of("problem_id", problemId, "markdown", markdown);
    }

    private static List<Map<String, Object>> emptyLast7(LocalDate day) {
        List<Map<String, Object>> last7 = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate d = day.minusDays(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", d.toString());
            item.put("submissions", 0);
            item.put("accepted", 0);
            last7.add(item);
        }
        return last7;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
