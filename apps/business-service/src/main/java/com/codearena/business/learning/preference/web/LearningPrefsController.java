package com.codearena.business.learning.preference.web;

import com.codearena.business.learning.list.domain.ProblemListRepository;
import com.codearena.business.learning.preference.domain.LearningPrefsEntity;
import com.codearena.business.learning.preference.domain.LearningPrefsRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 学习偏好（list_mode / kg_mode / active_list）。 */
@RestController
@RequiredArgsConstructor
public class LearningPrefsController {

    private final LearningPrefsRepository learningPrefsRepository;
    private final ProblemListRepository problemListRepository;

    @GetMapping("/api/learning")
    public Map<String, Object> getLearning() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("learning", currentLearning());
        body.put(
                "progress",
                Map.of(
                        "list_total", 0,
                        "list_done", 0,
                        "list_mastered", 0,
                        "kg_total", 0,
                        "kg_done", 0));
        body.put("lists", listSummaries());
        return body;
    }

    @PostMapping("/api/learning")
    @Transactional
    public ResponseEntity<?> updateLearning(@RequestBody Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "无更新字段"));
        }
        LearningPrefsEntity prefs = learningPrefsRepository
                .findFirstByOrderByIdAsc()
                .orElseGet(() -> {
                    LearningPrefsEntity created = new LearningPrefsEntity();
                    created.setListMode(true);
                    created.setKgMode(true);
                    created.setActiveListId("hot100");
                    created.setUpdatedAt(OffsetDateTime.now());
                    return created;
                });
        boolean touched = false;
        if (body.containsKey("list_mode")) {
            prefs.setListMode(Boolean.TRUE.equals(body.get("list_mode"))
                    || "true".equalsIgnoreCase(String.valueOf(body.get("list_mode"))));
            touched = true;
        }
        if (body.containsKey("kg_mode")) {
            prefs.setKgMode(Boolean.TRUE.equals(body.get("kg_mode"))
                    || "true".equalsIgnoreCase(String.valueOf(body.get("kg_mode"))));
            touched = true;
        }
        if (body.containsKey("active_list_id")) {
            String aid = String.valueOf(body.get("active_list_id")).trim();
            prefs.setActiveListId(aid.isEmpty() ? "hot100" : aid);
            touched = true;
        }
        if (!touched) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "无更新字段"));
        }
        prefs.setUpdatedAt(OffsetDateTime.now());
        learningPrefsRepository.save(prefs);

        Map<String, Object> learning = Map.of(
                "list_mode", prefs.getListMode(),
                "kg_mode", prefs.getKgMode(),
                "active_list_id", prefs.getActiveListId());
        return ResponseEntity.ok(Map.of("status", "ok", "learning", learning));
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

    private List<Map<String, Object>> listSummaries() {
        List<Map<String, Object>> lists = new ArrayList<>();
        problemListRepository.findAll().forEach(list -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", list.getId());
            item.put("name", list.getName());
            item.put("source", list.getSource());
            item.put("readonly", list.getReadonly());
            lists.add(item);
        });
        if (lists.isEmpty()) {
            lists.add(Map.of(
                    "id", "hot100",
                    "name", "Hot 100",
                    "source", "system",
                    "readonly", true));
        }
        return lists;
    }
}
