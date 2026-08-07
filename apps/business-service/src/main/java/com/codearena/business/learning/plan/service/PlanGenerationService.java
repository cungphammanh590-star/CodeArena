package com.codearena.business.learning.plan.service;

import com.codearena.business.learning.list.domain.ProblemListEntity;
import com.codearena.business.learning.list.domain.ProblemListItemEntity;
import com.codearena.business.learning.list.domain.ProblemListItemRepository;
import com.codearena.business.learning.list.domain.ProblemListRepository;
import com.codearena.business.learning.preference.domain.LearningPrefsEntity;
import com.codearena.business.learning.preference.domain.LearningPrefsRepository;
import com.codearena.business.learning.plan.domain.PlanDailyTaskEntity;
import com.codearena.business.learning.plan.domain.PlanDailyTaskRepository;
import com.codearena.business.learning.plan.domain.StudyPlanEntity;
import com.codearena.business.learning.plan.domain.StudyPlanRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PlanGenerationService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MIN_DAYS = 7;
    private static final int MAX_DAYS = 90;
    private static final int MIN_DAILY = 2;
    private static final int MAX_DAILY = 5;
    private static final int DEFAULT_LIMIT = 120;

    private final List<GoalPoolResolver> resolvers;
    private final StudyPlanRepository studyPlanRepository;
    private final PlanDailyTaskRepository dailyTaskRepository;
    private final ProblemListRepository listRepository;
    private final ProblemListItemRepository listItemRepository;
    private final LearningPrefsRepository prefsRepository;

    public record GenerateCommand(
            Long userId,
            String goalType,
            String goalRef,
            String title,
            Integer days,
            Integer dailyGoal,
            Boolean schedule,
            String difficulty,
            Integer limit) {}

    @Transactional
    public Map<String, Object> generate(GenerateCommand cmd) {
        if (cmd.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user required");
        }
        String goalType = normalizeGoalType(cmd.goalType());
        String goalRefRaw = cmd.goalRef() == null ? "" : cmd.goalRef().trim();
        if (goalRefRaw.isEmpty()) {
            return fail("goal_ref required");
        }

        var existing = studyPlanRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(
                cmd.userId(), StudyPlanEntity.STATUS_ACTIVE);
        if (existing.isPresent()) {
            StudyPlanEntity p = existing.get();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ok", false);
            data.put("plan_id", p.getId());
            data.put("list_id", p.getListId());
            data.put("note", "已有进行中的计划，请先暂停或完成后再创建（plan_id=" + p.getId() + "）");
            return data;
        }

        boolean schedule = cmd.schedule() == null || Boolean.TRUE.equals(cmd.schedule());
        int limit = cmd.limit() == null ? DEFAULT_LIMIT : Math.max(10, Math.min(200, cmd.limit()));
        String difficulty = cmd.difficulty();

        GoalPoolResolver resolver = resolvers.stream()
                .filter(r -> r.goalType().equals(goalType))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "unsupported goal_type: " + goalType));

        String goalRef = switch (goalType) {
            case "company" -> BankBackedPoolResolver.normalizeCompany(goalRefRaw);
            case "topic" -> BankBackedPoolResolver.normalizeTopic(goalRefRaw);
            case "list" -> goalRefRaw.isBlank() ? "hot100" : goalRefRaw;
            default -> goalRefRaw;
        };

        List<PoolItem> pool = resolver.resolve(goalRef, difficulty, limit);
        if (pool.isEmpty()) {
            return fail("题池为空：goal_type=" + goalType + " goal_ref=" + goalRef
                    + "。请换目标或先导入题库种子。");
        }

        int days = schedule ? clamp(cmd.days() == null ? 14 : cmd.days(), MIN_DAYS, MAX_DAYS) : 0;
        int dailyGoal = clamp(
                cmd.dailyGoal() == null
                        ? (schedule ? Math.max(MIN_DAILY, (int) Math.ceil(pool.size() / (double) Math.max(days, 1))) : MIN_DAILY)
                        : cmd.dailyGoal(),
                MIN_DAILY,
                MAX_DAILY);

        if (schedule) {
            int capacity = days * dailyGoal;
            if (pool.size() > capacity) {
                pool = new ArrayList<>(pool.subList(0, capacity));
            }
        }

        String title = cmd.title() == null || cmd.title().isBlank()
                ? defaultTitle(goalType, goalRef, days, schedule)
                : cmd.title().trim();

        String listId = "plan_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ProblemListEntity list = new ProblemListEntity();
        list.setId(listId);
        list.setName(title);
        list.setSource("plan_gen");
        list.setReadonly(false);
        list.setCreatedAt(OffsetDateTime.now());
        list.setUpdatedAt(OffsetDateTime.now());
        listRepository.save(list);

        int order = 0;
        for (PoolItem item : pool) {
            ProblemListItemEntity row = new ProblemListItemEntity();
            row.setListId(listId);
            row.setProblemId(item.problemId());
            row.setTitle(item.title());
            row.setSlug(item.slug());
            row.setDifficulty(item.difficulty() == null ? "Medium" : item.difficulty());
            row.setTagsJson("[]");
            row.setSortOrder(order++);
            listItemRepository.save(row);
        }

        touchActiveList(cmd.userId(), listId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("goal_type", goalType);
        data.put("goal_ref", goalRef);
        data.put("title", title);
        data.put("list_id", listId);
        data.put("total_questions", pool.size());

        if (!schedule) {
            data.put("schedule", false);
            data.put("note", "已创建题单（未排日程）；可在学习页浏览或再说「按 N 天打卡」生成日程。");
            return data;
        }

        LocalDate start = LocalDate.now(DEFAULT_ZONE);
        LocalDate end = start.plusDays(days - 1L);
        StudyPlanEntity plan = new StudyPlanEntity();
        plan.setUserId(cmd.userId());
        plan.setGoalType(goalType);
        plan.setGoalRef(goalRef);
        plan.setTitle(title);
        plan.setListId(listId);
        plan.setTotalDays(days);
        plan.setStartDate(start);
        plan.setEndDate(end);
        plan.setStatus(StudyPlanEntity.STATUS_ACTIVE);
        plan.setCreatedAt(OffsetDateTime.now());
        plan.setUpdatedAt(OffsetDateTime.now());
        plan = studyPlanRepository.save(plan);

        List<List<Integer>> buckets = bucketByDay(pool, days, dailyGoal);
        for (int d = 0; d < buckets.size(); d++) {
            PlanDailyTaskEntity task = new PlanDailyTaskEntity();
            task.setPlanId(plan.getId());
            task.setDayNum(d + 1);
            task.setScheduledDate(start.plusDays(d));
            task.setProblemIds(toJsonArray(buckets.get(d)));
            task.setStatus(PlanDailyTaskEntity.STATUS_PENDING);
            dailyTaskRepository.save(task);
        }

        int todayCount = buckets.isEmpty() ? 0 : buckets.get(0).size();
        data.put("schedule", true);
        data.put("plan_id", plan.getId());
        data.put("total_days", days);
        data.put("daily_goal", dailyGoal);
        data.put("daily_avg", Math.round(pool.size() * 10.0 / days) / 10.0);
        data.put("start_date", start.toString());
        data.put("end_date", end.toString());
        data.put("today_count", todayCount);
        data.put("stages", stageCounts(pool));
        data.put("note", "已生成题单并排入 " + days + " 天日程；可用 get_today_tasks 查看今日题目。");
        return data;
    }

    private void touchActiveList(Long userId, String listId) {
        LearningPrefsEntity prefs = prefsRepository
                .findFirstByUserIdOrderByIdAsc(userId)
                .or(() -> prefsRepository.findFirstByOrderByIdAsc())
                .orElseGet(() -> {
                    LearningPrefsEntity created = new LearningPrefsEntity();
                    created.setUserId(userId);
                    created.setListMode(true);
                    created.setKgMode(true);
                    return created;
                });
        prefs.setUserId(userId);
        prefs.setActiveListId(listId);
        prefs.setUpdatedAt(OffsetDateTime.now());
        prefsRepository.save(prefs);
    }

    private static List<List<Integer>> bucketByDay(List<PoolItem> pool, int days, int dailyGoal) {
        List<Integer> ids = pool.stream().map(PoolItem::problemId).collect(Collectors.toList());
        List<List<Integer>> buckets = new ArrayList<>();
        int idx = 0;
        for (int d = 0; d < days; d++) {
            List<Integer> day = new ArrayList<>();
            for (int k = 0; k < dailyGoal && idx < ids.size(); k++) {
                day.add(ids.get(idx++));
            }
            // last day eats remainder
            if (d == days - 1) {
                while (idx < ids.size()) {
                    day.add(ids.get(idx++));
                }
            }
            buckets.add(day);
        }
        return buckets;
    }

    private static Map<String, Integer> stageCounts(List<PoolItem> pool) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (PoolItem p : pool) {
            String s = p.stageHint() == null || p.stageHint().isBlank() ? "general" : p.stageHint();
            m.merge(s, 1, Integer::sum);
        }
        return m;
    }

    private static String defaultTitle(String goalType, String goalRef, int days, boolean schedule) {
        if (!schedule) {
            return goalRef + " 题单";
        }
        return switch (goalType) {
            case "company" -> goalRef + " 面试备考·" + days + "天";
            case "topic" -> goalRef + " 专题·" + days + "天";
            case "list" -> goalRef + " 打卡·" + days + "天";
            default -> goalRef + "·" + days + "天";
        };
    }

    private static String normalizeGoalType(String raw) {
        String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "company", "interview", "corp" -> "company";
            case "topic", "tag", "专题" -> "topic";
            case "list", "题单" -> "list";
            case "weak", "weakness" -> "weak";
            case "custom" -> "custom";
            default -> t.isEmpty() ? "topic" : t;
        };
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String toJsonArray(List<Integer> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    private static Map<String, Object> fail(String note) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", false);
        data.put("note", note);
        return data;
    }
}
