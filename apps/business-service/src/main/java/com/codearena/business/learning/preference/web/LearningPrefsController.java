package com.codearena.business.learning.preference.web;

import com.codearena.business.learning.list.domain.ProblemListRepository;
import com.codearena.business.learning.preference.domain.LearningPrefsEntity;
import com.codearena.business.learning.preference.service.LearningPrefsService;
import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
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

/** 学习偏好（list_mode / kg_mode / active_list）— 按用户隔离。 */
@RestController
@RequiredArgsConstructor
public class LearningPrefsController {

    private final LearningPrefsService learningPrefsService;
    private final ProblemListRepository problemListRepository;
    private final CurrentUserService currentUserService;

    @GetMapping("/api/learning")
    public Map<String, Object> getLearning(HttpServletRequest request) {
        UserEntity user = currentUserService.require(request);
        LearningPrefsEntity prefs = learningPrefsService.getOrCreate(user.getId());
        Map<String, Object> learning = learningPrefsService.toLearningMap(prefs);
        String activeListId = String.valueOf(learning.get("active_list_id"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("learning", learning);
        body.put("progress", learningPrefsService.computeListProgress(user.getId(), activeListId));
        body.put("lists", listSummaries(activeListId));
        return body;
    }

    @PostMapping("/api/learning")
    @Transactional
    public ResponseEntity<?> updateLearning(
            HttpServletRequest request, @RequestBody Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "无更新字段"));
        }
        UserEntity user = currentUserService.require(request);

        Boolean listMode = null;
        Boolean kgMode = null;
        String activeListId = null;
        boolean touched = false;

        if (body.containsKey("list_mode")) {
            listMode = Boolean.TRUE.equals(body.get("list_mode"))
                    || "true".equalsIgnoreCase(String.valueOf(body.get("list_mode")));
            touched = true;
        }
        if (body.containsKey("kg_mode")) {
            kgMode = Boolean.TRUE.equals(body.get("kg_mode"))
                    || "true".equalsIgnoreCase(String.valueOf(body.get("kg_mode")));
            touched = true;
        }
        if (body.containsKey("active_list_id")) {
            activeListId = String.valueOf(body.get("active_list_id")).trim();
            touched = true;
        }
        if (!touched) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "无更新字段"));
        }

        LearningPrefsEntity prefs =
                learningPrefsService.update(user.getId(), listMode, kgMode, activeListId);
        return ResponseEntity.ok(
                Map.of("status", "ok", "learning", learningPrefsService.toLearningMap(prefs)));
    }

    private List<Map<String, Object>> listSummaries(String activeListId) {
        List<Map<String, Object>> lists = new ArrayList<>();
        problemListRepository.findAll().forEach(list -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", list.getId());
            item.put("name", list.getName());
            item.put("source", list.getSource());
            item.put("readonly", list.getReadonly());
            item.put("active", list.getId().equals(activeListId));
            lists.add(item);
        });
        if (lists.isEmpty()) {
            lists.add(Map.of(
                    "id", "hot100",
                    "name", "Hot 100",
                    "source", "system",
                    "readonly", true,
                    "active", true));
        }
        return lists;
    }
}
