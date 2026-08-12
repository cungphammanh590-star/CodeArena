package com.codearena.business.learning.plan.web;

import com.codearena.business.learning.plan.service.PlanQueryService;
import com.codearena.business.learning.preference.domain.LearningPrefsEntity;
import com.codearena.business.learning.preference.service.LearningPrefsService;
import com.codearena.business.learning.srs.SrsService;
import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 今日计划（排期）+ 今日复习（SRS 到期）。 */
@RestController
@RequiredArgsConstructor
public class ReviewPlanController {

    private final LearningPrefsService learningPrefsService;
    private final PlanQueryService planQueryService;
    private final SrsService srsService;
    private final CurrentUserService currentUserService;

    @GetMapping("/api/review/today")
    public Map<String, Object> reviewToday(
            HttpServletRequest request, @RequestParam(defaultValue = "20") int limit) {
        UserEntity user = currentUserService.require(request);
        LearningPrefsEntity prefs = learningPrefsService.getOrCreate(user.getId());
        Map<String, Object> learning = learningPrefsService.toLearningMap(prefs);
        String activeListId = String.valueOf(learning.get("active_list_id"));

        Map<String, Object> planToday = planQueryService.todayTasks(user.getId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> planItems = planToday.get("items") instanceof List<?> list
                ? new ArrayList<>((List<Map<String, Object>>) list)
                : new ArrayList<>();
        for (Map<String, Object> item : planItems) {
            item.putIfAbsent("kind", "plan");
        }
        if (planItems.size() > limit) {
            planItems = planItems.subList(0, limit);
        }

        Map<String, Object> reviewToday = srsService.dueToday(user.getId(), limit);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reviewItems = reviewToday.get("items") instanceof List<?> list
                ? new ArrayList<>((List<Map<String, Object>>) list)
                : new ArrayList<>();

        // 计划与复习重叠：复习项保留，计划项标注 both
        Set<Integer> reviewIds = reviewItems.stream()
                .map(m -> toInt(m.get("problem_id")))
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        for (Map<String, Object> item : planItems) {
            Integer pid = toInt(item.get("problem_id"));
            if (pid != null && reviewIds.contains(pid)) {
                item.put("kind", "both");
                String reason = String.valueOf(item.getOrDefault("reason", "今日计划"));
                item.put("reason", reason + " · 亦到期复习");
            }
        }

        List<Map<String, Object>> queue = new ArrayList<>(planItems.size() + reviewItems.size());
        queue.addAll(planItems);
        for (Map<String, Object> item : reviewItems) {
            Integer pid = toInt(item.get("problem_id"));
            boolean alreadyInPlan = pid != null
                    && planItems.stream().anyMatch(p -> pid.equals(toInt(p.get("problem_id"))));
            if (!alreadyInPlan) {
                queue.add(item);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("plan", planToday);
        body.put("plan_items", planItems);
        body.put("plan_count", planItems.size());
        body.put("review", reviewToday);
        body.put("review_items", reviewItems);
        body.put("review_count", reviewItems.size());
        // 兼容旧前端：due = 仅 SRS 复习
        body.put("due", reviewItems);
        body.put("queue", queue);
        body.put("count", queue.size());
        body.put("progress", learningPrefsService.computeListProgress(user.getId(), activeListId));
        body.put("learning", learning);
        return body;
    }

    private static Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
