package com.codearena.business.learning.plan.web;

import com.codearena.business.learning.preference.domain.LearningPrefsRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 复习计划 / 今日队列（桩；后续接 SRS）。 */
@RestController
@RequiredArgsConstructor
public class ReviewPlanController {

    private final LearningPrefsRepository learningPrefsRepository;

    @GetMapping("/api/review/today")
    public Map<String, Object> reviewToday(@RequestParam(defaultValue = "20") int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("due", List.of());
        body.put("queue", List.of());
        body.put("count", 0);
        body.put(
                "progress",
                Map.of(
                        "list_total", 0,
                        "list_done", 0,
                        "list_mastered", 0));
        body.put("learning", currentLearning());
        return body;
    }

    private Map<String, Object> currentLearning() {
        return learningPrefsRepository
                .findFirstByOrderByIdAsc()
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
