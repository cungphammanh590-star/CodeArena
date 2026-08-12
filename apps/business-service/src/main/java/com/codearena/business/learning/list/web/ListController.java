package com.codearena.business.learning.list.web;

import com.codearena.business.learning.list.domain.ProblemListEntity;
import com.codearena.business.learning.list.domain.ProblemListItemEntity;
import com.codearena.business.learning.list.domain.ProblemListItemRepository;
import com.codearena.business.learning.list.domain.ProblemListRepository;
import com.codearena.business.learning.preference.service.LearningPrefsService;
import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ListController {

    private final ProblemListRepository problemListRepository;
    private final ProblemListItemRepository problemListItemRepository;
    private final LearningPrefsService learningPrefsService;
    private final CurrentUserService currentUserService;

    @GetMapping("/api/lists")
    public Map<String, Object> lists() {
        return Map.of("status", "ok", "lists", listSummaries());
    }

    @GetMapping(value = "/api/lists/sample", produces = MediaType.APPLICATION_JSON_VALUE)
    public String sample(@RequestParam(defaultValue = "list") String kind) {
        if ("single".equalsIgnoreCase(kind)) {
            return """
                    {
                      "problem_id": 1,
                      "slug": "two-sum",
                      "title": "Two Sum",
                      "difficulty": "Easy",
                      "tags": ["Array", "Hash Table"]
                    }
                    """;
        }
        return """
                {
                  "name": "Sample List",
                  "problems": [
                    {
                      "problem_id": 1,
                      "slug": "two-sum",
                      "title": "Two Sum",
                      "difficulty": "Easy",
                      "tags": ["Array", "Hash Table"]
                    }
                  ]
                }
                """;
    }

    @GetMapping("/api/lists/{listId}")
    public ResponseEntity<?> detail(@PathVariable String listId) {
        return problemListRepository
                .findById(listId)
                .map(list -> {
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (ProblemListItemEntity item :
                            problemListItemRepository.findByListIdOrderBySortOrderAsc(listId)) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("problem_id", item.getProblemId());
                        row.put("slug", item.getSlug());
                        row.put("title", item.getTitle());
                        row.put("difficulty", item.getDifficulty());
                        row.put("tags_json", item.getTagsJson());
                        row.put("sort_order", item.getSortOrder());
                        items.add(row);
                    }
                    Map<String, Object> listMap = new LinkedHashMap<>();
                    listMap.put("id", list.getId());
                    listMap.put("name", list.getName());
                    listMap.put("source", list.getSource());
                    listMap.put("readonly", list.getReadonly());

                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("status", "ok");
                    body.put("list", listMap);
                    body.put("list_id", listId);
                    body.put("items", items);
                    body.put("total", items.size());
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("status", "error", "message", "题单不存在: " + listId)));
    }

    @PostMapping("/api/lists")
    @Transactional
    public ResponseEntity<?> create(
            HttpServletRequest request, @RequestBody Map<String, Object> body) {
        UserEntity user = currentUserService.require(request);
        String name = body == null ? "" : String.valueOf(body.getOrDefault("name", "")).trim();
        if (name.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "请填写题单名称"));
        }
        String listId = body.get("list_id") == null
                ? null
                : String.valueOf(body.get("list_id")).trim();
        if (listId == null || listId.isEmpty()) {
            listId = "list-" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (problemListRepository.existsById(listId)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "题单 ID 已存在: " + listId));
        }
        ProblemListEntity entity = new ProblemListEntity();
        entity.setId(listId);
        entity.setName(name);
        entity.setSource("user");
        entity.setReadonly(false);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());
        problemListRepository.save(entity);

        if (Boolean.TRUE.equals(body.get("set_active"))) {
            learningPrefsService.setActiveList(user.getId(), listId);
        }

        Map<String, Object> listMap = new LinkedHashMap<>();
        listMap.put("id", entity.getId());
        listMap.put("name", entity.getName());
        listMap.put("source", entity.getSource());
        listMap.put("readonly", entity.getReadonly());
        return ResponseEntity.ok(Map.of("status", "ok", "list", listMap));
    }

    @PostMapping("/api/lists/active")
    @Transactional
    public ResponseEntity<?> setActiveList(
            HttpServletRequest request, @RequestBody Map<String, Object> body) {
        UserEntity user = currentUserService.require(request);
        String listId = body == null ? "" : String.valueOf(body.getOrDefault("list_id", "")).trim();
        if (listId.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "list_id required"));
        }
        if ("hot100".equals(listId) || Boolean.TRUE.equals(body.get("restore_default"))) {
            listId = "hot100";
        } else if (!problemListRepository.existsById(listId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", "error", "message", "题单不存在: " + listId));
        }
        Map<String, Object> learning = learningPrefsService.setActiveList(user.getId(), listId);
        return ResponseEntity.ok(Map.of("status", "ok", "learning", learning));
    }

    @PostMapping("/api/lists/import")
    @Transactional
    public ResponseEntity<?> importList(
            HttpServletRequest request, @RequestBody Map<String, Object> body) {
        UserEntity user = currentUserService.require(request);
        if (body == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "请先选择或新建题单"));
        }
        String listId = String.valueOf(body.getOrDefault("list_id", "")).trim();
        if (listId.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "请先选择或新建题单"));
        }
        if (!problemListRepository.existsById(listId)) {
            if (Boolean.TRUE.equals(body.get("create_if_missing"))) {
                ProblemListEntity created = new ProblemListEntity();
                created.setId(listId);
                created.setName(String.valueOf(body.getOrDefault("name", listId)));
                created.setSource("user");
                created.setReadonly(false);
                created.setCreatedAt(OffsetDateTime.now());
                created.setUpdatedAt(OffsetDateTime.now());
                problemListRepository.save(created);
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("status", "error", "message", "题单不存在: " + listId));
            }
        }

        Object problemsRaw = body.get("problems");
        if (problemsRaw == null && body.get("data") instanceof Map<?, ?> dataMap) {
            problemsRaw = dataMap.get("problems");
        }
        int imported = 0;
        if (problemsRaw instanceof List<?> problems) {
            int order = (int) problemListItemRepository.countByListId(listId);
            for (Object p : problems) {
                if (!(p instanceof Map<?, ?> pm)) {
                    continue;
                }
                Integer problemId = toInt(pm.get("problem_id"));
                if (problemId == null) {
                    continue;
                }
                ProblemListItemEntity item = new ProblemListItemEntity();
                item.setListId(listId);
                item.setProblemId(problemId);
                item.setSlug(mapStr(pm, "slug", "problem-" + problemId));
                item.setTitle(mapStr(pm, "title", "Problem " + problemId));
                item.setDifficulty(mapStr(pm, "difficulty", "Medium"));
                item.setTagsJson(mapStr(pm, "tags", "[]"));
                item.setSortOrder(order++);
                problemListItemRepository.save(item);
                imported++;
            }
        }

        if (Boolean.TRUE.equals(body.get("set_active"))) {
            learningPrefsService.setActiveList(user.getId(), listId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("list_id", listId);
        result.put("imported", imported);
        result.put("mode", String.valueOf(body.getOrDefault("mode", "append")));
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/api/lists/{listId}/items/{problemId}")
    @Transactional
    public ResponseEntity<?> removeItem(@PathVariable String listId, @PathVariable Integer problemId) {
        if (!problemListRepository.existsById(listId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", "error", "message", "题单不存在: " + listId));
        }
        problemListItemRepository.deleteByListIdAndProblemId(listId, problemId);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "list_id", listId,
                "problem_id", problemId,
                "removed", true));
    }

    @DeleteMapping("/api/lists/{listId}")
    @Transactional
    public ResponseEntity<?> deleteList(
            HttpServletRequest request, @PathVariable String listId) {
        UserEntity user = currentUserService.require(request);
        return problemListRepository
                .findById(listId)
                .map(list -> {
                    if (Boolean.TRUE.equals(list.getReadonly()) || "hot100".equals(listId)) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("status", "error", "message", "只读题单不可删除"));
                    }
                    problemListItemRepository
                            .findByListIdOrderBySortOrderAsc(listId)
                            .forEach(problemListItemRepository::delete);
                    problemListRepository.delete(list);
                    Map<String, Object> learning =
                            learningPrefsService.setActiveList(user.getId(), "hot100");
                    return ResponseEntity.ok(Map.of(
                            "status", "ok",
                            "deleted", listId,
                            "learning", learning));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("status", "error", "message", "题单不存在: " + listId)));
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
        return lists;
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

    private static String mapStr(Map<?, ?> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }
}
