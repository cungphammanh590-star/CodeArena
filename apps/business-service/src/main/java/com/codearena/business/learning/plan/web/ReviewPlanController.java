package com.codearena.business.learning.plan.web;

import com.codearena.business.learning.plan.service.PlanQueryService;
import com.codearena.business.learning.preference.domain.LearningPrefsRepository;
import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 今日复习 / 计划任务。 */
@RestController
@RequiredArgsConstructor
public class ReviewPlanController {

    private final LearningPrefsRepository learningPrefsRepository;
    private final PlanQueryService planQueryService;
    private final CurrentUserService currentUserService;

    @GetMapping("/api/review/today")
    public Map<String, Object> reviewToday(
            HttpServletRequest request, @RequestParam(defaultValue = "20") int limit) {
        UserEntity user = currentUserService.require(request);
        Map<String, Object> planToday = planQueryService.todayTasks(user.getId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = planToday.get("items") instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();
        if (items.size() > limit) {
            items = items.subList(0, limit);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("due", items);
        body.put("queue", items);
        body.put("count", items.size());
        body.put("plan", planToday);
        body.put(
                "progress",
                Map.of(
                        "list_total", 0,
                        "list_done", 0,
                        "list_mastered", 0));
        body.put("learning", currentLearning(user.getId()));
        return body;
    }

    private Map<String, Object> currentLearning(Long userId) {
        return learningPrefsRepository
                .findFirstByUserIdOrderByIdAsc(userId)
                .or(() -> learningPrefsRepository.findFirstByOrderByIdAsc())
                .map(p -> Map.<String, Object>of(
                        "list_mode", p.getListMode(),
                        "kg_mode", p.getKgMode(),
                        "active_list_id", p.getActiveListId()))
                .orElse(Map.of(
                        "list_mode", true,
                        "kg_mode", true,
                        "active_list_id", "hot100"));
    }
}
