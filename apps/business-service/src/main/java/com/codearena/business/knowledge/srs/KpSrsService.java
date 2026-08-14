package com.codearena.business.knowledge.srs;

import com.codearena.business.knowledge.domain.KbDocumentEntity;
import com.codearena.business.knowledge.domain.KbDocumentRepository;
import com.codearena.business.knowledge.domain.KbKnowledgePointEntity;
import com.codearena.business.knowledge.domain.KbKnowledgePointRepository;
import com.codearena.business.knowledge.ingest.KnowledgeTextCleaner;
import com.codearena.business.knowledge.srs.domain.UserKpSrsEntity;
import com.codearena.business.knowledge.srs.domain.UserKpSrsRepository;
import com.codearena.business.learning.srs.SrsScheduler;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class KpSrsService {

    private final UserKpSrsRepository srsRepository;
    private final KbKnowledgePointRepository kpRepository;
    private final KbDocumentRepository documentRepository;

    /** 新卡立刻可学（due=now）；不是「明天再出现在复习里」。 */
    @Transactional
    public void enroll(Long userId, Long kpId) {
        if (userId == null || kpId == null) {
            return;
        }
        Optional<UserKpSrsEntity> opt = srsRepository.findByUserIdAndKnowledgePointId(userId, kpId);
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        if (opt.isPresent()) {
            UserKpSrsEntity card = opt.get();
            if (Boolean.TRUE.equals(card.getSuspended())) {
                card.setSuspended(false);
                if (card.getDueAt() == null || card.getReps() == null || card.getReps() == 0) {
                    card.setDueAt(now);
                }
                card.setUpdatedAt(now);
                srsRepository.save(card);
            }
            return;
        }
        UserKpSrsEntity card = new UserKpSrsEntity();
        card.setUserId(userId);
        card.setKnowledgePointId(kpId);
        card.setEase(2.5f);
        card.setIntervalDays(0);
        card.setReps(0);
        card.setLapses(0);
        card.setDueAt(now);
        card.setLastOutcome(null);
        card.setSuspended(false);
        card.setUpdatedAt(now);
        srsRepository.save(card);
    }

    @Transactional
    public void removeForKp(Long kpId) {
        if (kpId != null) {
            srsRepository.deleteByKnowledgePointId(kpId);
        }
    }

    /**
     * 学习队列：未学新卡（reps=0）+ 今日到期复习。
     * 入库后即可学，不必先有「学习计划」。
     */
    @Transactional
    public Map<String, Object> dueToday(Long userId, int limit) {
        int capped = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 50));
        ensureEnrolled(userId);

        OffsetDateTime until = endOfToday();
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        List<UserKpSrsEntity> due = srsRepository.findDue(userId, until);
        // 把「明天才 due」的旧新卡也拉进队列：reps=0 且从未复习 → 视为可学，提前 due
        List<UserKpSrsEntity> patched = new ArrayList<>();
        for (UserKpSrsEntity card : due) {
            patched.add(card);
        }
        // 补漏：库里 reps=0 但 due 在未来的卡（旧 enroll 行为）
        for (UserKpSrsEntity card : srsRepository.findByUserId(userId)) {
            if (Boolean.TRUE.equals(card.getSuspended())) {
                continue;
            }
            if (isNew(card) && card.getDueAt() != null && card.getDueAt().isAfter(now)) {
                card.setDueAt(now);
                srsRepository.save(card);
                if (patched.stream().noneMatch(c -> c.getKnowledgePointId().equals(card.getKnowledgePointId()))) {
                    patched.add(card);
                }
            }
        }

        patched.sort(Comparator.comparing((UserKpSrsEntity c) -> isNew(c) ? 0 : 1)
                .thenComparing(UserKpSrsEntity::getDueAt, Comparator.nullsLast(Comparator.naturalOrder())));

        List<Map<String, Object>> items = new ArrayList<>();
        int newCount = 0;
        int reviewCount = 0;
        for (UserKpSrsEntity card : patched) {
            if (items.size() >= capped) {
                break;
            }
            Optional<KbKnowledgePointEntity> kpOpt = kpRepository.findByIdAndUserIdAndStatus(
                    card.getKnowledgePointId(), userId, KbKnowledgePointEntity.STATUS_READY);
            if (kpOpt.isEmpty()) {
                continue;
            }
            boolean neu = isNew(card);
            if (neu) {
                newCount++;
            } else {
                reviewCount++;
            }
            Map<String, Object> view = toCardView(card, kpOpt.get(), true);
            view.put("kind", neu ? "new" : "review");
            items.add(view);
        }
        long dueTotal = srsRepository.countDue(userId, until);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("items", items);
        data.put("count", items.size());
        data.put("due_total", dueTotal);
        data.put("new_count", newCount);
        data.put("review_count", reviewCount);
        data.put("scheduled_date", LocalDate.now(SrsScheduler.ZONE).toString());
        data.put("note", "队列含未学新卡与到期复习；无需单独学习计划");
        return data;
    }

    @Transactional(readOnly = true)
    public long countDueToday(Long userId) {
        if (userId == null) {
            return 0;
        }
        return srsRepository.countDue(userId, endOfToday());
    }

    @Transactional
    public Map<String, Object> review(Long userId, Long kpId, String gradeRaw) {
        KbKnowledgePointEntity kp = kpRepository
                .findByIdAndUserIdAndStatus(kpId, userId, KbKnowledgePointEntity.STATUS_READY)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "knowledge point not found"));
        OffsetDateTime now = OffsetDateTime.now();
        UserKpSrsEntity card = srsRepository
                .findByUserIdAndKnowledgePointId(userId, kpId)
                .orElseGet(() -> {
                    enroll(userId, kpId);
                    return srsRepository
                            .findByUserIdAndKnowledgePointId(userId, kpId)
                            .orElseThrow();
                });
        if (Boolean.TRUE.equals(card.getSuspended())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "card suspended");
        }
        SrsScheduler.Grade grade = SrsScheduler.Grade.fromLabel(gradeRaw);
        applySnapshot(
                card,
                SrsScheduler.apply(
                        card.getEase() == null ? 2.5f : card.getEase(),
                        card.getIntervalDays() == null ? 0 : card.getIntervalDays(),
                        card.getReps() == null ? 0 : card.getReps(),
                        card.getLapses() == null ? 0 : card.getLapses(),
                        grade,
                        now),
                now);
        card.setLastReviewedAt(now);
        srsRepository.save(card);
        Map<String, Object> out = toCardView(card, kp, false);
        out.put("grade", grade.label);
        out.put("kind", "review");
        return out;
    }

    /** 为尚无 SRS 行的 ready KP 建卡，保证打开复习就能学。 */
    private void ensureEnrolled(Long userId) {
        List<KbKnowledgePointEntity> ready =
                kpRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, KbKnowledgePointEntity.STATUS_READY);
        Set<Long> have = srsRepository.findByUserId(userId).stream()
                .map(UserKpSrsEntity::getKnowledgePointId)
                .collect(Collectors.toSet());
        for (KbKnowledgePointEntity kp : ready) {
            if (!have.contains(kp.getId())) {
                enroll(userId, kp.getId());
            }
        }
    }

    private static boolean isNew(UserKpSrsEntity card) {
        return (card.getReps() == null || card.getReps() == 0) && card.getLastReviewedAt() == null;
    }

    private Map<String, Object> toCardView(UserKpSrsEntity card, KbKnowledgePointEntity kp, boolean withAnswer) {
        String sourceTitle = documentRepository
                .findById(kp.getDocumentId())
                .map(KbDocumentEntity::getTitle)
                .orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("knowledge_point_id", kp.getId());
        m.put("title", kp.getTitle());
        String question =
                kp.getQuestion() != null && !kp.getQuestion().isBlank() ? kp.getQuestion() : kp.getTitle();
        m.put("question", KnowledgeTextCleaner.reflowText(question));
        m.put("topic", kp.getTopic());
        m.put("source_title", sourceTitle);
        m.put("refined", Boolean.TRUE.equals(kp.getRefined()));
        m.put("due_at", card.getDueAt() == null ? null : card.getDueAt().toString());
        m.put("ease", card.getEase());
        m.put("interval_days", card.getIntervalDays());
        m.put("reps", card.getReps());
        if (withAnswer) {
            String answer = kp.getAnswer() != null && !kp.getAnswer().isBlank() ? kp.getAnswer() : kp.getBody();
            m.put("answer", KnowledgeTextCleaner.reflowText(answer));
            m.put("key_points_json", kp.getKeyPointsJson());
        }
        return m;
    }

    private static void applySnapshot(
            UserKpSrsEntity card, SrsScheduler.Snapshot snap, OffsetDateTime now) {
        card.setEase(snap.ease());
        card.setIntervalDays(snap.intervalDays());
        card.setReps(snap.reps());
        card.setLapses(snap.lapses());
        card.setDueAt(snap.dueAt());
        card.setLastOutcome(snap.outcome());
        card.setUpdatedAt(now);
        if (card.getSuspended() == null) {
            card.setSuspended(false);
        }
    }

    private static OffsetDateTime endOfToday() {
        return LocalDate.now(SrsScheduler.ZONE)
                .plusDays(1)
                .atStartOfDay(SrsScheduler.ZONE)
                .toOffsetDateTime()
                .minusSeconds(1);
    }
}
