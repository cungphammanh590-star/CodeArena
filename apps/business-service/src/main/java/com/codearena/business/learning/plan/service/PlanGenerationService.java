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
import com.codearena.business.problem.domain.ProblemEntity;
import com.codearena.business.problem.domain.ProblemRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final ProblemRepository problemRepository;
    private final ProblemResolveService problemResolveService;

    public record GenerateCommand(
            Long userId,
            String goalType,
            String goalRef,
            String title,
            Integer days,
            Integer dailyGoal,
            Boolean schedule,
            String difficulty,
            Integer limit,
            List<Integer> problemIds,
            Boolean skipPassed,
            Boolean force) {}

    /** 只算不写：容量推算 + 已刷过滤结果。 */
    public Map<String, Object> preview(GenerateCommand cmd) {
        if (cmd.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user required");
        }
        PoolBuild built = buildPool(cmd, true);
        if (!built.ok()) {
            return built.error();
        }
        CapacityPlan cap = resolveCapacity(built.pool().size(), cmd.days(), cmd.dailyGoal(), cmd.force());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", !cap.needUserChoice());
        data.put("preview", true);
        data.put("goal_type", built.goalType());
        data.put("goal_ref", built.goalRef());
        data.put("title", built.titleHint(cmd.title()));
        data.put("problem_ids", built.pool().stream().map(PoolItem::problemId).collect(Collectors.toList()));
        data.put("total_questions", built.pool().size());
        data.put("passed_count", built.passedCount());
        data.put("remaining_count", built.pool().size());
        data.put("skip_passed", built.skipPassed());
        data.put("days", cap.days());
        data.put("daily_goal", cap.dailyGoal());
        data.put("capacity", cap.days() * cap.dailyGoal());
        data.put("need_user_choice", cap.needUserChoice());
        data.put("choice_reason", cap.reason());
        data.put("choices", cap.choices());
        data.put(
                "note",
                cap.needUserChoice()
                        ? cap.reason()
                        : ("预览：共 "
                                + built.pool().size()
                                + " 题待刷，"
                                + cap.days()
                                + " 天 × 每天 "
                                + cap.dailyGoal()
                                + " 道。确认后调用 generate_study_plan。"));
        return data;
    }

    @Transactional
    public Map<String, Object> generate(GenerateCommand cmd) {
        if (cmd.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user required");
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

        PoolBuild built = buildPool(cmd, false);
        if (!built.ok()) {
            return built.error();
        }

        boolean schedule = cmd.schedule() == null || Boolean.TRUE.equals(cmd.schedule());
        if (!schedule) {
            return writeListOnly(cmd, built);
        }

        CapacityPlan cap = resolveCapacity(built.pool().size(), cmd.days(), cmd.dailyGoal(), cmd.force());
        if (cap.needUserChoice()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ok", false);
            data.put("need_user_choice", true);
            data.put("choice_reason", cap.reason());
            data.put("choices", cap.choices());
            data.put("total_questions", built.pool().size());
            data.put("days", cap.days());
            data.put("daily_goal", cap.dailyGoal());
            data.put("note", cap.reason() + " 请先用 ask_user 让用户选择，或带 force=true 按推荐值生成。");
            return data;
        }

        List<PoolItem> pool = new ArrayList<>(built.pool());
        int days = cap.days();
        int dailyGoal = cap.dailyGoal();
        // 极端：仍超容量时截断并注明（仅 force 路径）
        int capacity = days * dailyGoal;
        boolean truncated = false;
        if (pool.size() > capacity) {
            pool = new ArrayList<>(pool.subList(0, capacity));
            truncated = true;
        }

        String title = cmd.title() == null || cmd.title().isBlank()
                ? built.titleHint(null)
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

        LocalDate start = LocalDate.now(DEFAULT_ZONE);
        LocalDate end = start.plusDays(days - 1L);
        StudyPlanEntity plan = new StudyPlanEntity();
        plan.setUserId(cmd.userId());
        plan.setGoalType(built.goalType());
        plan.setGoalRef(built.goalRef());
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
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("goal_type", built.goalType());
        data.put("goal_ref", built.goalRef());
        data.put("title", title);
        data.put("list_id", listId);
        data.put("total_questions", pool.size());
        data.put("schedule", true);
        data.put("plan_id", plan.getId());
        data.put("total_days", days);
        data.put("daily_goal", dailyGoal);
        data.put("daily_avg", Math.round(pool.size() * 10.0 / days) / 10.0);
        data.put("start_date", start.toString());
        data.put("end_date", end.toString());
        data.put("today_count", todayCount);
        data.put("stages", stageCounts(pool));
        data.put("truncated", truncated);
        data.put(
                "note",
                "已生成题单并排入 "
                        + days
                        + " 天日程；可用 get_today_tasks 查看今日题目。"
                        + (truncated ? "（题量超出容量，已按优先级截断）" : ""));
        return data;
    }

    private Map<String, Object> writeListOnly(GenerateCommand cmd, PoolBuild built) {
        String title = cmd.title() == null || cmd.title().isBlank()
                ? built.goalRef() + " 题单"
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
        for (PoolItem item : built.pool()) {
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
        data.put("goal_type", built.goalType());
        data.put("goal_ref", built.goalRef());
        data.put("title", title);
        data.put("list_id", listId);
        data.put("total_questions", built.pool().size());
        data.put("schedule", false);
        data.put("note", "已创建题单（未排日程）；可在学习页浏览或再说「按 N 天打卡」生成日程。");
        return data;
    }

    private PoolBuild buildPool(GenerateCommand cmd, boolean forPreview) {
        List<Integer> customIds = normalizeIds(cmd.problemIds());
        boolean skipPassed = cmd.skipPassed() == null || Boolean.TRUE.equals(cmd.skipPassed());

        if (!customIds.isEmpty()) {
            List<PoolItem> pool = loadCustomPool(customIds);
            int before = pool.size();
            int passed = 0;
            if (skipPassed && cmd.userId() != null) {
                Map<String, Object> resolved =
                        problemResolveService.resolve(cmd.userId(), customIds.stream().map(String::valueOf).toList(), null);
                @SuppressWarnings("unchecked")
                List<Integer> remaining = (List<Integer>) resolved.getOrDefault("remaining_ids", List.of());
                @SuppressWarnings("unchecked")
                List<Integer> passedIds = (List<Integer>) resolved.getOrDefault("passed_ids", List.of());
                passed = passedIds.size();
                Set<Integer> keep = new LinkedHashSet<>(remaining);
                pool = pool.stream().filter(p -> keep.contains(p.problemId())).collect(Collectors.toList());
            }
            if (pool.isEmpty()) {
                return PoolBuild.fail("自定义题单为空或均已刷过（skip_passed=" + skipPassed + "，原始 "
                        + before + " 题）。");
            }
            String ref = cmd.goalRef() == null || cmd.goalRef().isBlank() ? "custom" : cmd.goalRef().trim();
            return new PoolBuild(true, "custom", ref, pool, passed, skipPassed, null);
        }

        String goalType = normalizeGoalType(cmd.goalType());
        String goalRefRaw = cmd.goalRef() == null ? "" : cmd.goalRef().trim();
        if (goalRefRaw.isEmpty()) {
            return PoolBuild.fail("goal_ref required（或传 problem_ids）");
        }
        if ("custom".equals(goalType)) {
            return PoolBuild.fail("goal_type=custom 时请传 problem_ids（先 resolve_problem_refs）");
        }

        int limit = cmd.limit() == null ? DEFAULT_LIMIT : Math.max(10, Math.min(200, cmd.limit()));
        GoalPoolResolver resolver = resolvers.stream()
                .filter(r -> r.goalType().equals(goalType))
                .findFirst()
                .orElse(null);
        if (resolver == null) {
            return PoolBuild.fail("unsupported goal_type: " + goalType);
        }
        String goalRef = switch (goalType) {
            case "company" -> BankBackedPoolResolver.normalizeCompany(goalRefRaw);
            case "topic" -> BankBackedPoolResolver.normalizeTopic(goalRefRaw);
            case "list" -> goalRefRaw.isBlank() ? "hot100" : goalRefRaw;
            default -> goalRefRaw;
        };
        List<PoolItem> pool = resolver.resolve(goalRef, cmd.difficulty(), limit);
        if (pool.isEmpty()) {
            return PoolBuild.fail("题池为空：goal_type=" + goalType + " goal_ref=" + goalRef);
        }
        int passed = 0;
        if (skipPassed && cmd.userId() != null) {
            List<Integer> ids = pool.stream().map(PoolItem::problemId).toList();
            Map<String, Object> resolved =
                    problemResolveService.resolve(cmd.userId(), ids.stream().map(String::valueOf).toList(), null);
            @SuppressWarnings("unchecked")
            List<Integer> remaining = (List<Integer>) resolved.getOrDefault("remaining_ids", List.of());
            @SuppressWarnings("unchecked")
            List<Integer> passedIds = (List<Integer>) resolved.getOrDefault("passed_ids", List.of());
            passed = passedIds.size();
            Set<Integer> keep = new LinkedHashSet<>(remaining);
            pool = pool.stream().filter(p -> keep.contains(p.problemId())).collect(Collectors.toList());
            if (pool.isEmpty()) {
                return PoolBuild.fail("题池题目均已刷过；可设 skip_passed=false 强制重排。");
            }
        }
        return new PoolBuild(true, goalType, goalRef, pool, passed, skipPassed, null);
    }

    private List<PoolItem> loadCustomPool(List<Integer> ids) {
        List<PoolItem> out = new ArrayList<>();
        int order = 0;
        for (Integer id : ids) {
            ProblemEntity p = problemRepository.findByProblemId(id).orElse(null);
            if (p == null) {
                continue;
            }
            out.add(new PoolItem(
                    p.getProblemId(),
                    p.getTitle(),
                    p.getSlug(),
                    p.getDifficulty(),
                    "custom",
                    order++));
        }
        return out;
    }

    /**
     * 容量规则：只给天数→推每日；只给强度→推天数；两边都给且装不下→需用户选择。
     */
    static CapacityPlan resolveCapacity(int n, Integer daysIn, Integer dailyIn, Boolean force) {
        boolean hasDays = daysIn != null && daysIn > 0;
        boolean hasDaily = dailyIn != null && dailyIn > 0;
        boolean forceOk = Boolean.TRUE.equals(force);

        if (!hasDays && !hasDaily) {
            int days = 14;
            int daily = clamp(Math.max(MIN_DAILY, (int) Math.ceil(n / (double) days)), MIN_DAILY, MAX_DAILY);
            return CapacityPlan.ok(days, daily, "默认 14 天推算每日题量");
        }
        if (hasDays && !hasDaily) {
            int days = clamp(daysIn, MIN_DAYS, MAX_DAYS);
            int rawDaily = (int) Math.ceil(n / (double) Math.max(days, 1));
            if (rawDaily > MAX_DAILY && !forceOk) {
                return CapacityPlan.need(
                        days,
                        MAX_DAILY,
                        "题量 "
                                + n
                                + " 在 "
                                + days
                                + " 天内需每天约 "
                                + rawDaily
                                + " 道，超过建议上限 "
                                + MAX_DAILY
                                + "。",
                        List.of(
                                Map.of("id", "raise_days", "label", "放宽天数", "hint", "按每天 " + MAX_DAILY + " 道重算天数"),
                                Map.of(
                                        "id",
                                        "raise_daily",
                                        "label",
                                        "提高强度",
                                        "hint",
                                        "坚持 " + days + " 天，每天 " + rawDaily + " 道"),
                                Map.of("id", "top_n", "label", "只保留 Top 容量", "hint", "保留前 " + (days * MAX_DAILY) + " 题")));
            }
            int daily = clamp(rawDaily, MIN_DAILY, Math.max(MAX_DAILY, rawDaily));
            if (!forceOk) {
                daily = clamp(rawDaily, MIN_DAILY, MAX_DAILY);
            }
            return CapacityPlan.ok(days, daily, "按天数推算每日题量");
        }
        if (!hasDays) {
            int daily = clamp(dailyIn, MIN_DAILY, MAX_DAILY);
            int rawDays = (int) Math.ceil(n / (double) Math.max(daily, 1));
            int days = clamp(rawDays, MIN_DAYS, MAX_DAYS);
            if (days * daily < n && !forceOk) {
                return CapacityPlan.need(
                        days,
                        daily,
                        "按每天 "
                                + daily
                                + " 道最多排 "
                                + days
                                + " 天（"
                                + (days * daily)
                                + " 题），装不下全部 "
                                + n
                                + " 题。",
                        List.of(
                                Map.of("id", "raise_daily", "label", "提高每日题量", "hint", "缩短总天数"),
                                Map.of("id", "top_n", "label", "只保留可排题目", "hint", "截断到 " + (days * daily) + " 题")));
            }
            return CapacityPlan.ok(days, daily, "按每日强度推算天数");
        }

        int days = clamp(daysIn, MIN_DAYS, MAX_DAYS);
        int daily = clamp(dailyIn, MIN_DAILY, MAX_DAILY);
        if (days * daily < n && !forceOk) {
            int needDaily = (int) Math.ceil(n / (double) days);
            int needDays = (int) Math.ceil(n / (double) daily);
            return CapacityPlan.need(
                    days,
                    daily,
                    "你定了 "
                            + days
                            + " 天 × 每天 "
                            + daily
                            + " 道（容量 "
                            + (days * daily)
                            + "），但待刷 "
                            + n
                            + " 题，装不下。",
                    List.of(
                            Map.of(
                                    "id",
                                    "raise_days",
                                    "label",
                                    "放宽到 " + needDays + " 天",
                                    "hint",
                                    "保持每天 " + daily + " 道"),
                            Map.of(
                                    "id",
                                    "raise_daily",
                                    "label",
                                    "提高到每天 " + needDaily + " 道",
                                    "hint",
                                    "保持 " + days + " 天"),
                            Map.of(
                                    "id",
                                    "top_n",
                                    "label",
                                    "只刷前 " + (days * daily) + " 题",
                                    "hint",
                                    "按频次截断")));
        }
        return CapacityPlan.ok(days, daily, "按用户给定天数与强度");
    }

    private void touchActiveList(Long userId, String listId) {
        LearningPrefsEntity prefs = prefsRepository
                .findFirstByUserIdOrderByIdAsc(userId)
                .orElseGet(() -> {
                    LearningPrefsEntity created = new LearningPrefsEntity();
                    created.setUserId(userId);
                    created.setListMode(true);
                    created.setKgMode(true);
                    created.setActiveListId("hot100");
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

    private static List<Integer> normalizeIds(List<Integer> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Integer> set = new LinkedHashSet<>();
        for (Integer id : raw) {
            if (id != null && id > 0) {
                set.add(id);
            }
        }
        return new ArrayList<>(set);
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

    record CapacityPlan(
            int days, int dailyGoal, boolean needUserChoice, String reason, List<Map<String, String>> choices) {
        static CapacityPlan ok(int days, int daily, String reason) {
            return new CapacityPlan(days, daily, false, reason, List.of());
        }

        static CapacityPlan need(int days, int daily, String reason, List<Map<String, String>> choices) {
            return new CapacityPlan(days, daily, true, reason, choices);
        }
    }

    private record PoolBuild(
            boolean ok,
            String goalType,
            String goalRef,
            List<PoolItem> pool,
            int passedCount,
            boolean skipPassed,
            String errorNote) {
        static PoolBuild fail(String note) {
            return new PoolBuild(false, "", "", List.of(), 0, true, note);
        }

        Map<String, Object> error() {
            return PlanGenerationService.fail(errorNote == null ? "题池构建失败" : errorNote);
        }

        String titleHint(String override) {
            if (override != null && !override.isBlank()) {
                return override.trim();
            }
            if ("custom".equals(goalType)) {
                return goalRef + " · 自定义题单";
            }
            return goalRef + " · 刷题计划";
        }
    }
}
