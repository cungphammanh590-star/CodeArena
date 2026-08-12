package com.codearena.business.learning.srs;

import com.codearena.business.learning.mastery.domain.UserProblemFlagEntity;
import com.codearena.business.learning.mastery.domain.UserProblemFlagRepository;
import com.codearena.business.learning.plan.domain.GoalProblemBankEntity;
import com.codearena.business.learning.plan.domain.GoalProblemBankRepository;
import com.codearena.business.learning.srs.domain.UserProblemSrsEntity;
import com.codearena.business.learning.srs.domain.UserProblemSrsRepository;
import com.codearena.business.problem.domain.ProblemEntity;
import com.codearena.business.problem.domain.ProblemRepository;
import com.codearena.business.submission.domain.SubmissionRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SrsService {

    private static final int BACKFILL_BATCH = 200;

    private final UserProblemSrsRepository srsRepository;
    private final UserProblemFlagRepository flagRepository;
    private final SubmissionRepository submissionRepository;
    private final ProblemRepository problemRepository;
    private final GoalProblemBankRepository bankRepository;

    @Transactional
    public void recordSubmission(Long userId, Integer problemId, String status) {
        if (userId == null || problemId == null || problemId <= 0) {
            return;
        }
        boolean accepted = "Accepted".equalsIgnoreCase(String.valueOf(status));
        Optional<UserProblemSrsEntity> opt = srsRepository.findByUserIdAndProblemId(userId, problemId);
        if (opt.isEmpty()) {
            if (!accepted) {
                return;
            }
            if (isMastered(userId, problemId)) {
                return;
            }
            enrollNew(userId, problemId, OffsetDateTime.now());
            return;
        }
        UserProblemSrsEntity card = opt.get();
        if (Boolean.TRUE.equals(card.getSuspended())) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        SrsScheduler.Grade grade = accepted ? SrsScheduler.Grade.GOOD : SrsScheduler.Grade.AGAIN;
        applyGrade(card, grade, now);
        srsRepository.save(card);
    }

    @Transactional
    public void setSuspended(Long userId, Integer problemId, boolean suspended) {
        if (userId == null || problemId == null || problemId <= 0) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        Optional<UserProblemSrsEntity> opt = srsRepository.findByUserIdAndProblemId(userId, problemId);
        UserProblemSrsEntity card;
        if (opt.isPresent()) {
            card = opt.get();
        } else if (suspended) {
            card = newCard(userId, problemId, now);
            writeSnapshot(card, SrsScheduler.enroll(now), now);
        } else {
            return;
        }
        card.setSuspended(suspended);
        card.setUpdatedAt(now);
        if (!suspended && card.getDueAt() == null) {
            card.setDueAt(now.plusDays(1).truncatedTo(ChronoUnit.SECONDS));
        }
        srsRepository.save(card);
    }

    @Transactional
    public Map<String, Object> dueToday(Long userId, int limit) {
        int capped = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 50));
        ensureBackfill(userId);

        OffsetDateTime until = endOfToday();
        List<UserProblemSrsEntity> due = srsRepository.findDue(userId, until);
        // 再滤一遍掌握（防 flags 与 suspended 不同步）
        Set<Integer> mastered = masteredIds(userId);
        List<UserProblemSrsEntity> filtered = due.stream()
                .filter(c -> !mastered.contains(c.getProblemId()))
                .limit(capped)
                .toList();

        List<Map<String, Object>> items = enrich(filtered);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("items", items);
        data.put("count", items.size());
        data.put("due_total", srsRepository.countDue(userId, until));
        data.put("scheduled_date", LocalDate.now(SrsScheduler.ZONE).toString());
        if (items.isEmpty()) {
            data.put("note", "今日无到期复习题（已 AC 题会进入间隔复习队列）");
        }
        return data;
    }

    @Transactional
    public long countDueToday(Long userId) {
        if (userId == null) {
            return 0;
        }
        ensureBackfill(userId);
        return srsRepository.countDue(userId, endOfToday());
    }

    @Transactional
    public Map<String, Object> cardDigest(Long userId, Integer problemId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("problem_id", problemId);
        Optional<UserProblemSrsEntity> opt = srsRepository.findByUserIdAndProblemId(userId, problemId);
        if (opt.isEmpty()) {
            data.put("has_card", false);
            return data;
        }
        UserProblemSrsEntity c = opt.get();
        data.put("has_card", true);
        data.put("ease", c.getEase());
        data.put("interval_days", c.getIntervalDays());
        data.put("reps", c.getReps());
        data.put("lapses", c.getLapses());
        data.put("due_at", c.getDueAt() == null ? null : c.getDueAt().toString());
        data.put("last_outcome", c.getLastOutcome());
        data.put("suspended", Boolean.TRUE.equals(c.getSuspended()));
        data.put(
                "last_reviewed_at",
                c.getLastReviewedAt() == null ? null : c.getLastReviewedAt().toString());
        return data;
    }

    private void ensureBackfill(Long userId) {
        if (userId == null) {
            return;
        }
        List<Object[]> lastAcRows = submissionRepository.findLastAcceptedAtByUser(userId);
        if (lastAcRows.isEmpty()) {
            return;
        }
        Set<Integer> mastered = masteredIds(userId);
        Set<Integer> existing = srsRepository.findByUserId(userId).stream()
                .map(UserProblemSrsEntity::getProblemId)
                .collect(Collectors.toCollection(HashSet::new));

        OffsetDateTime now = OffsetDateTime.now();
        int created = 0;
        for (Object[] row : lastAcRows) {
            if (row == null || row.length < 2 || !(row[0] instanceof Integer pid)) {
                continue;
            }
            if (existing.contains(pid) || mastered.contains(pid)) {
                continue;
            }
            OffsetDateTime lastAc = row[1] instanceof OffsetDateTime odt
                    ? odt
                    : now.minusDays(3);
            // 首次建卡：默认「上次 AC 后第 1 天该复习」；已逾期则标为现在到期
            OffsetDateTime due = lastAc.plusDays(1).truncatedTo(ChronoUnit.SECONDS);
            if (due.isAfter(now)) {
                // 刚过不久：等到期日
            } else {
                due = now.truncatedTo(ChronoUnit.SECONDS);
            }
            UserProblemSrsEntity card = newCard(userId, pid, now);
            card.setEase(2.5f);
            card.setIntervalDays(1);
            card.setReps(1);
            card.setLapses(0);
            card.setDueAt(due);
            card.setLastOutcome("good");
            card.setLastReviewedAt(lastAc);
            card.setSuspended(false);
            srsRepository.save(card);
            existing.add(pid);
            created++;
            if (created >= BACKFILL_BATCH) {
                break;
            }
        }

        // 掌握题挂起
        for (Integer pid : mastered) {
            srsRepository.findByUserIdAndProblemId(userId, pid).ifPresent(card -> {
                if (!Boolean.TRUE.equals(card.getSuspended())) {
                    card.setSuspended(true);
                    card.setUpdatedAt(now);
                    srsRepository.save(card);
                }
            });
        }
    }

    private void enrollNew(Long userId, Integer problemId, OffsetDateTime now) {
        UserProblemSrsEntity card = newCard(userId, problemId, now);
        writeSnapshot(card, SrsScheduler.enroll(now), now);
        srsRepository.save(card);
    }

    private void applyGrade(UserProblemSrsEntity card, SrsScheduler.Grade grade, OffsetDateTime now) {
        SrsScheduler.Snapshot snap = SrsScheduler.apply(
                card.getEase() == null ? 2.5f : card.getEase(),
                card.getIntervalDays() == null ? 0 : card.getIntervalDays(),
                card.getReps() == null ? 0 : card.getReps(),
                card.getLapses() == null ? 0 : card.getLapses(),
                grade,
                now);
        writeSnapshot(card, snap, now);
    }

    private static void writeSnapshot(
            UserProblemSrsEntity card, SrsScheduler.Snapshot snap, OffsetDateTime now) {
        card.setEase(snap.ease());
        card.setIntervalDays(snap.intervalDays());
        card.setReps(snap.reps());
        card.setLapses(snap.lapses());
        card.setDueAt(snap.dueAt());
        card.setLastOutcome(snap.outcome());
        card.setLastReviewedAt(now);
        card.setUpdatedAt(now);
    }

    private static UserProblemSrsEntity newCard(Long userId, Integer problemId, OffsetDateTime now) {
        UserProblemSrsEntity card = new UserProblemSrsEntity();
        card.setUserId(userId);
        card.setProblemId(problemId);
        card.setCreatedAt(now);
        card.setUpdatedAt(now);
        card.setSuspended(false);
        return card;
    }

    private boolean isMastered(Long userId, Integer problemId) {
        return flagRepository
                .findByUserIdAndProblemId(userId, problemId)
                .map(f -> Boolean.TRUE.equals(f.getMastered()))
                .orElse(false);
    }

    private Set<Integer> masteredIds(Long userId) {
        return flagRepository.findByUserIdAndMasteredTrue(userId).stream()
                .map(UserProblemFlagEntity::getProblemId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private List<Map<String, Object>> enrich(List<UserProblemSrsEntity> cards) {
        if (cards.isEmpty()) {
            return List.of();
        }
        Set<Integer> ids = cards.stream()
                .map(UserProblemSrsEntity::getProblemId)
                .collect(Collectors.toCollection(HashSet::new));

        Map<Integer, ProblemEntity> byProblem = problemRepository.findAll().stream()
                .filter(p -> p.getProblemId() != null && ids.contains(p.getProblemId()))
                .collect(Collectors.toMap(ProblemEntity::getProblemId, Function.identity(), (a, b) -> a));
        Map<Integer, GoalProblemBankEntity> byBank = bankRepository.findAll().stream()
                .filter(b -> b.getProblemId() != null && ids.contains(b.getProblemId()))
                .collect(Collectors.toMap(
                        GoalProblemBankEntity::getProblemId, Function.identity(), (a, b) -> a));

        LocalDate today = LocalDate.now(SrsScheduler.ZONE);
        List<Map<String, Object>> items = new ArrayList<>();
        for (UserProblemSrsEntity c : cards) {
            Integer pid = c.getProblemId();
            ProblemEntity p = byProblem.get(pid);
            GoalProblemBankEntity bank = byBank.get(pid);
            String title = null;
            String difficulty = null;
            String slug = null;
            if (p != null) {
                title = p.getTitle();
                difficulty = p.getDifficulty();
                slug = p.getSlug();
            }
            if ((title == null || title.isBlank()) && bank != null) {
                title = bank.getTitle();
                if (difficulty == null || difficulty.isBlank()) {
                    difficulty = bank.getDifficulty();
                }
                if (slug == null || slug.isBlank()) {
                    slug = bank.getSlug();
                }
            }
            if (title == null || title.isBlank()) {
                title = "LC " + pid;
            }
            if (difficulty == null || difficulty.isBlank()) {
                difficulty = "Medium";
            }

            long overdueDays = 0;
            if (c.getDueAt() != null) {
                LocalDate dueDay = c.getDueAt().atZoneSameInstant(SrsScheduler.ZONE).toLocalDate();
                overdueDays = Math.max(0, ChronoUnit.DAYS.between(dueDay, today));
            }
            String reason;
            if (overdueDays > 0) {
                reason = "间隔复习 · 逾期 " + overdueDays + " 天"
                        + (c.getIntervalDays() != null && c.getIntervalDays() > 0
                                ? "（上次间隔 " + c.getIntervalDays() + " 天）"
                                : "");
            } else {
                reason = "间隔复习 · 今日到期"
                        + (c.getIntervalDays() != null && c.getIntervalDays() > 0
                                ? "（间隔 " + c.getIntervalDays() + " 天）"
                                : "");
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", "review");
            item.put("problem_id", pid);
            item.put("id", pid);
            item.put("title", title);
            item.put("difficulty", difficulty);
            item.put("slug", slug);
            item.put("reason", reason);
            item.put("ease", c.getEase());
            item.put("interval_days", c.getIntervalDays());
            item.put("reps", c.getReps());
            item.put("due_at", c.getDueAt() == null ? null : c.getDueAt().toString());
            items.add(item);
        }
        return items;
    }

    private static OffsetDateTime endOfToday() {
        LocalDate today = LocalDate.now(SrsScheduler.ZONE);
        return today.plusDays(1)
                .atStartOfDay(SrsScheduler.ZONE)
                .toOffsetDateTime()
                .minusNanos(1);
    }
}
