package com.codearena.business.learning.plan.service;

import com.codearena.business.learning.plan.domain.PlanDailyTaskEntity;
import com.codearena.business.learning.plan.domain.PlanDailyTaskRepository;
import com.codearena.business.learning.plan.domain.StudyPlanEntity;
import com.codearena.business.learning.plan.domain.StudyPlanRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PlanQueryService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private final StudyPlanRepository studyPlanRepository;
    private final PlanDailyTaskRepository dailyTaskRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional(readOnly = true)
    public Optional<StudyPlanEntity> findActive(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return studyPlanRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(
                userId, StudyPlanEntity.STATUS_ACTIVE);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> activePlanDigest(Long userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        Optional<StudyPlanEntity> opt = findActive(userId);
        if (opt.isEmpty()) {
            data.put("ok", true);
            data.put("has_plan", false);
            data.put("note", "当前无进行中的刷题计划");
            return data;
        }
        StudyPlanEntity plan = opt.get();
        LocalDate today = LocalDate.now(DEFAULT_ZONE);
        data.put("ok", true);
        data.put("has_plan", true);
        data.put("plan_id", plan.getId());
        data.put("goal_type", plan.getGoalType());
        data.put("goal_ref", plan.getGoalRef());
        data.put("title", plan.getTitle());
        data.put("list_id", plan.getListId());
        data.put("status", plan.getStatus());
        data.put("total_days", plan.getTotalDays());
        data.put("start_date", plan.getStartDate() == null ? null : plan.getStartDate().toString());
        data.put("end_date", plan.getEndDate() == null ? null : plan.getEndDate().toString());
        if (plan.getEndDate() != null) {
            data.put("days_left", Math.max(0, ChronoUnit.DAYS.between(today, plan.getEndDate())));
        }
        dailyTaskRepository
                .findFirstByPlanIdAndScheduledDate(plan.getId(), today)
                .ifPresent(t -> {
                    data.put("today_day_num", t.getDayNum());
                    data.put("today_problem_ids", parseIds(t.getProblemIds()));
                    data.put("today_status", t.getStatus());
                });
        return data;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> todayTasks(Long userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        Optional<StudyPlanEntity> opt = findActive(userId);
        if (opt.isEmpty()) {
            data.put("ok", true);
            data.put("items", List.of());
            data.put("note", "无进行中的计划；可用 generate_study_plan 创建");
            return data;
        }
        StudyPlanEntity plan = opt.get();
        LocalDate today = LocalDate.now(DEFAULT_ZONE);
        Optional<PlanDailyTaskEntity> task =
                dailyTaskRepository.findFirstByPlanIdAndScheduledDate(plan.getId(), today);
        List<Integer> ids = task.map(t -> parseIds(t.getProblemIds())).orElse(List.of());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Integer pid : ids) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("problem_id", pid);
            items.add(item);
        }
        data.put("ok", true);
        data.put("plan_id", plan.getId());
        data.put("goal_type", plan.getGoalType());
        data.put("goal_ref", plan.getGoalRef());
        data.put("title", plan.getTitle());
        data.put("list_id", plan.getListId());
        data.put("scheduled_date", today.toString());
        data.put("day_num", task.map(PlanDailyTaskEntity::getDayNum).orElse(null));
        data.put("status", task.map(PlanDailyTaskEntity::getStatus).orElse(null));
        data.put("items", items);
        data.put("count", items.size());
        if (items.isEmpty()) {
            data.put("note", "今日无排期任务（可能未到开始日、已结束或未排日程）");
        }
        return data;
    }

    private List<Integer> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Integer>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }
}
