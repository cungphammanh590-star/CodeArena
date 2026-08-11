package com.codearena.business.problem.web;

import com.codearena.business.problem.domain.ProblemDailyStatsEntity;
import com.codearena.business.problem.domain.ProblemEntity;
import com.codearena.business.problem.domain.ProblemRepository;
import com.codearena.business.problem.domain.ProblemStatsEntity;
import com.codearena.business.problem.domain.ProblemStatsRepository;
import com.codearena.business.problem.domain.ProblemDailyStatsRepository;
import com.codearena.business.submission.domain.SubmissionEntity;
import com.codearena.business.submission.domain.SubmissionRepository;
import com.codearena.business.learning.mastery.domain.UserProblemFlagRepository;
import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");

    private final SubmissionRepository submissionRepository;
    private final ProblemRepository problemRepository;
    private final ProblemStatsRepository problemStatsRepository;
    private final ProblemDailyStatsRepository problemDailyStatsRepository;
    private final UserProblemFlagRepository userProblemFlagRepository;
    private final CurrentUserService currentUserService;

    @GetMapping("/api/stats")
    public ResponseEntity<?> stats(
            HttpServletRequest request, @RequestParam(value = "date", required = false) String date) {
        UserEntity user = currentUserService.require(request);

        LocalDate day = LocalDate.now(CHINA);
        if (date != null && !date.isBlank()) {
            try {
                day = LocalDate.parse(date.trim());
            } catch (DateTimeParseException ex) {
                return ResponseEntity.badRequest()
                        .body(Map.of("status", "error", "message", "无效日期，请使用 YYYY-MM-DD"));
            }
        }

        ZonedDateTime dayStart = day.atStartOfDay(CHINA);
        ZonedDateTime dayEnd = day.plusDays(1).atStartOfDay(CHINA);
        ZonedDateTime weekStart = day.minusDays(6).atStartOfDay(CHINA);

        List<SubmissionEntity> daySubs =
                submissionRepository
                        .findByUserIdAndSubmittedAtGreaterThanEqualAndSubmittedAtLessThanOrderBySubmittedAtDesc(
                                user.getId(),
                                dayStart.toOffsetDateTime(),
                                dayEnd.toOffsetDateTime());

        List<SubmissionEntity> weekSubs =
                submissionRepository.findByUserIdAndSubmittedAtGreaterThanEqualOrderBySubmittedAtDesc(
                        user.getId(), weekStart.toOffsetDateTime());

        List<SubmissionEntity> recentSubs =
                submissionRepository.findTop80ByUserIdOrderBySubmittedAtDesc(user.getId());

        long total = submissionRepository.countByUserId(user.getId());
        long accepted = submissionRepository.countByUserIdAndStatus(user.getId(), "Accepted");
        long todayAccepted =
                daySubs.stream().filter(s -> "Accepted".equals(s.getStatus())).count();

        Map<Integer, ProblemEntity> problemCache = new HashMap<>();
        List<Map<String, Object>> todayItems = mapSubmissionItems(daySubs, problemCache);
        List<Map<String, Object>> recent = mapSubmissionItems(
                recentSubs.stream().limit(30).toList(), problemCache);
        List<Map<String, Object>> todayWrong = buildTodayWrong(daySubs, problemCache);
        List<Map<String, Object>> last7 = buildLast7(day, weekSubs);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", day.toString());
        body.put("user_public_id", user.getPublicId());
        body.put("username", user.getUsername());
        body.put("total_submissions", total);
        body.put("accepted_count", accepted);
        body.put("acceptance_rate", total == 0 ? 0.0 : round(100.0 * accepted / total));
        body.put("easy_solved", 0);
        body.put("medium_solved", 0);
        body.put("hard_solved", 0);
        body.put("today_submissions", daySubs.size());
        body.put("today_accepted", todayAccepted);
        body.put(
                "today_acceptance_rate",
                daySubs.isEmpty() ? 0.0 : round(100.0 * todayAccepted / daySubs.size()));
        body.put("streak_days", computeStreak(user.getId(), day));
        body.put("recent", recent);
        body.put("today_items", todayItems);
        body.put("today_wrong", todayWrong);
        body.put("last7", last7);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/problems")
    public Map<String, Object> problems(HttpServletRequest request) {
        UserEntity user = currentUserService.require(request);
        List<SubmissionEntity> all =
                submissionRepository.findTop80ByUserIdOrderBySubmittedAtDesc(user.getId());
        // 取更多：若用户提交很多，上面 limit 不够；再拉全量按用户
        all = submissionRepository.findByUserIdAndSubmittedAtGreaterThanEqualOrderBySubmittedAtDesc(
                user.getId(),
                java.time.OffsetDateTime.parse("2000-01-01T00:00:00Z"));

        Map<Integer, Agg> agg = new LinkedHashMap<>();
        Map<Integer, ProblemEntity> problemCache = new HashMap<>();
        for (SubmissionEntity s : all) {
            Agg a = agg.computeIfAbsent(s.getProblemId(), id -> new Agg());
            a.totalAttempts += 1;
            if ("Accepted".equals(s.getStatus())) {
                a.acceptedCount += 1;
            } else {
                a.wrongCount += 1;
            }
            a.lastStatus = s.getStatus();
            if (a.lastSubmittedAt == null
                    || (s.getSubmittedAt() != null && s.getSubmittedAt().isAfter(a.lastSubmittedAt))) {
                a.lastSubmittedAt = s.getSubmittedAt();
                a.lastStatus = s.getStatus();
            }
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<Integer, Agg> e : agg.entrySet()) {
            Integer pid = e.getKey();
            Agg a = e.getValue();
            ProblemEntity p = problemCache.computeIfAbsent(
                    pid, id -> problemRepository.findByProblemId(id).orElse(null));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("problem_id", pid);
            item.put("title", p != null ? p.getTitle() : ("Problem " + pid));
            item.put("title_slug", p != null ? p.getSlug() : ("problem-" + pid));
            item.put("difficulty", p != null ? p.getDifficulty() : null);
            item.put("topic_tags", p != null ? p.getTags() : null);
            item.put("total_attempts", a.totalAttempts);
            item.put("accepted_count", a.acceptedCount);
            item.put("wrong_count", a.wrongCount);
            item.put(
                    "acceptance_rate",
                    a.totalAttempts == 0
                            ? 0.0
                            : round(100.0 * a.acceptedCount / a.totalAttempts));
            item.put("last_status", a.lastStatus);
            item.put(
                    "last_submitted_at",
                    a.lastSubmittedAt == null ? null : a.lastSubmittedAt.toString());
            item.put(
                    "struggle_score",
                    a.totalAttempts == 0 ? 0.0 : (double) a.wrongCount / a.totalAttempts);
            items.add(item);
        }
        return Map.of("problems", items);
    }

    @GetMapping("/api/problems/{problemId}/stats")
    public ResponseEntity<?> problemStats(
            HttpServletRequest request, @PathVariable Integer problemId) {
        UserEntity user = currentUserService.require(request);
        ProblemEntity problem = problemRepository.findByProblemId(problemId).orElse(null);
        if (problem == null && problemStatsRepository.findById(problemId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", "error", "message", "not found"));
        }

        List<SubmissionEntity> subs =
                submissionRepository.findTop80ByProblemIdOrderBySubmittedAtDesc(problemId).stream()
                        .filter(s -> Objects.equals(s.getUserId(), user.getId()))
                        .toList();

        long accepted = subs.stream().filter(s -> "Accepted".equals(s.getStatus())).count();
        long wrong = subs.size() - accepted;

        Map<String, Object> problemView = new LinkedHashMap<>();
        problemView.put("problem_id", problemId);
        problemView.put(
                "title",
                problem != null
                        ? problem.getTitle()
                        : problemStatsRepository
                                .findById(problemId)
                                .map(ProblemStatsEntity::getTitle)
                                .orElse("Problem " + problemId));
        problemView.put(
                "title_slug",
                problem != null
                        ? problem.getSlug()
                        : problemStatsRepository
                                .findById(problemId)
                                .map(ProblemStatsEntity::getTitleSlug)
                                .orElse("problem-" + problemId));
        problemView.put(
                "difficulty",
                problem != null
                        ? problem.getDifficulty()
                        : problemStatsRepository
                                .findById(problemId)
                                .map(ProblemStatsEntity::getDifficulty)
                                .orElse(null));
        problemView.put("topic_tags", problem != null ? problem.getTags() : null);
        problemView.put("total_attempts", subs.size());
        problemView.put("accepted_count", accepted);
        problemView.put("wrong_count", wrong);
        problemView.put("status_breakdown", Map.of());
        problemView.put(
                "acceptance_rate",
                subs.isEmpty() ? 0.0 : round(100.0 * accepted / subs.size()));
        problemView.put("struggle_score", wrong);
        problemView.put("avg_attempts_to_ac", null);
        problemView.put(
                "last_status", subs.isEmpty() ? null : subs.get(0).getStatus());
        problemView.put(
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
        for (SubmissionEntity s : subs) {
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
        body.put("problem", problemView);
        body.put("daily", daily);
        body.put("submissions", submissions);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/problems/{problemId}/llm-context")
    public Map<String, Object> llmContext(@PathVariable Integer problemId) {
        String markdown = problemRepository
                .findByProblemId(problemId)
                .map(row -> "# Problem " + problemId + "\n\n"
                        + "Title: " + nullToEmpty(row.getTitle()) + "\n"
                        + "Difficulty: " + nullToEmpty(row.getDifficulty()) + "\n")
                .orElseGet(() -> problemStatsRepository
                        .findById(problemId)
                        .map(row -> "# Problem " + problemId + "\n\n"
                                + "Title: " + nullToEmpty(row.getTitle()) + "\n"
                                + "Difficulty: " + nullToEmpty(row.getDifficulty()) + "\n"
                                + "Attempts: " + row.getTotalAttempts() + "\n"
                                + "Accepted: " + row.getAcceptedCount() + "\n")
                        .orElse("# Problem " + problemId + "\n\n(no stats yet)\n"));
        return Map.of("problem_id", problemId, "markdown", markdown);
    }

    private List<Map<String, Object>> mapSubmissionItems(
            List<SubmissionEntity> subs, Map<Integer, ProblemEntity> problemCache) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (SubmissionEntity s : subs) {
            ProblemEntity p = problemCache.computeIfAbsent(
                    s.getProblemId(),
                    id -> problemRepository.findByProblemId(id).orElse(null));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("problem_id", s.getProblemId());
            item.put("title", p != null ? p.getTitle() : ("Problem " + s.getProblemId()));
            item.put("difficulty", p != null ? p.getDifficulty() : null);
            item.put("status", s.getStatus());
            item.put("runtime_ms", s.getRuntimeMs());
            item.put(
                    "submitted_at",
                    s.getSubmittedAt() == null ? null : s.getSubmittedAt().toString());
            item.put("submission_id", s.getSubmissionId());
            items.add(item);
        }
        return items;
    }

    private List<Map<String, Object>> buildTodayWrong(
            List<SubmissionEntity> daySubs, Map<Integer, ProblemEntity> problemCache) {
        Map<Integer, Map<String, Integer>> counts = new LinkedHashMap<>();
        Map<Integer, String> titles = new HashMap<>();
        Map<Integer, String> diffs = new HashMap<>();
        for (SubmissionEntity s : daySubs) {
            if ("Accepted".equals(s.getStatus())) {
                continue;
            }
            counts.computeIfAbsent(s.getProblemId(), id -> new LinkedHashMap<>());
            Map<String, Integer> c = counts.get(s.getProblemId());
            c.put(s.getStatus(), c.getOrDefault(s.getStatus(), 0) + 1);
            ProblemEntity p = problemCache.computeIfAbsent(
                    s.getProblemId(),
                    id -> problemRepository.findByProblemId(id).orElse(null));
            titles.put(
                    s.getProblemId(),
                    p != null ? p.getTitle() : ("Problem " + s.getProblemId()));
            diffs.put(s.getProblemId(), p != null ? p.getDifficulty() : null);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, Integer>> e : counts.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("problem_id", e.getKey());
            item.put("title", titles.get(e.getKey()));
            item.put("difficulty", diffs.get(e.getKey()));
            item.put("status_counts", e.getValue());
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> buildLast7(LocalDate day, List<SubmissionEntity> weekSubs) {
        Map<LocalDate, int[]> buckets = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            buckets.put(day.minusDays(i), new int[] {0, 0});
        }
        for (SubmissionEntity s : weekSubs) {
            if (s.getSubmittedAt() == null) {
                continue;
            }
            LocalDate d = s.getSubmittedAt().atZoneSameInstant(CHINA).toLocalDate();
            int[] b = buckets.get(d);
            if (b == null) {
                continue;
            }
            b[0] += 1;
            if ("Accepted".equals(s.getStatus())) {
                b[1] += 1;
            }
        }
        List<Map<String, Object>> last7 = new ArrayList<>();
        for (Map.Entry<LocalDate, int[]> e : buckets.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", e.getKey().toString());
            item.put("submissions", e.getValue()[0]);
            item.put("accepted", e.getValue()[1]);
            last7.add(item);
        }
        return last7;
    }

    private int computeStreak(Long userId, LocalDate today) {
        int streak = 0;
        for (int i = 0; i < 365; i++) {
            LocalDate d = today.minusDays(i);
            ZonedDateTime start = d.atStartOfDay(CHINA);
            ZonedDateTime end = d.plusDays(1).atStartOfDay(CHINA);
            List<SubmissionEntity> day =
                    submissionRepository
                            .findByUserIdAndSubmittedAtGreaterThanEqualAndSubmittedAtLessThanOrderBySubmittedAtDesc(
                                    userId, start.toOffsetDateTime(), end.toOffsetDateTime());
            if (day.isEmpty()) {
                if (i == 0) {
                    continue; // 今天还没提交不打断历史 streak
                }
                break;
            }
            streak += 1;
        }
        return streak;
    }

    private static final class Agg {
        int totalAttempts;
        int acceptedCount;
        int wrongCount;
        String lastStatus;
        java.time.OffsetDateTime lastSubmittedAt;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
