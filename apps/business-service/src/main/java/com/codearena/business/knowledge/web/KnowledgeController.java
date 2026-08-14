package com.codearena.business.knowledge.web;

import com.codearena.business.knowledge.service.KnowledgeService;
import com.codearena.business.knowledge.srs.KpSrsService;
import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final KpSrsService kpSrsService;
    private final CurrentUserService currentUserService;

    @PostMapping(value = "/api/knowledge/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> createJson(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        UserEntity user = currentUserService.require(request);
        String title = str(body.get("title"));
        String sourceType = str(body.get("source_type"));
        String content = str(body.get("content"));
        Map<String, Object> doc = knowledgeService.createTextDocument(user.getId(), title, sourceType, content);
        return Map.of("status", "ok", "document", doc);
    }

    @PostMapping(value = "/api/knowledge/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> createMultipart(
            HttpServletRequest request,
            @RequestParam(value = "title", required = false) String title,
            @RequestPart("file") MultipartFile file)
            throws Exception {
        UserEntity user = currentUserService.require(request);
        Map<String, Object> doc = knowledgeService.createPdfDocument(user.getId(), title, file);
        return Map.of("status", "ok", "document", doc);
    }

    @GetMapping("/api/knowledge/documents")
    public Map<String, Object> listDocuments(HttpServletRequest request) {
        UserEntity user = currentUserService.require(request);
        List<Map<String, Object>> items = knowledgeService.listDocuments(user.getId());
        return Map.of("status", "ok", "documents", items);
    }

    @GetMapping("/api/knowledge/documents/{id}")
    public Map<String, Object> getDocument(HttpServletRequest request, @PathVariable Long id) {
        UserEntity user = currentUserService.require(request);
        return Map.of("status", "ok", "document", knowledgeService.getDocument(user.getId(), id));
    }

    @DeleteMapping("/api/knowledge/documents/{id}")
    public Map<String, Object> deleteDocument(HttpServletRequest request, @PathVariable Long id) {
        UserEntity user = currentUserService.require(request);
        knowledgeService.deleteDocument(user.getId(), id);
        return Map.of("status", "ok");
    }

    @PostMapping("/api/knowledge/documents/{id}/reprocess")
    public Map<String, Object> reprocess(HttpServletRequest request, @PathVariable Long id) {
        UserEntity user = currentUserService.require(request);
        return Map.of("status", "ok", "document", knowledgeService.reprocess(user.getId(), id));
    }

    @GetMapping("/api/knowledge/kps")
    public Map<String, Object> listKps(HttpServletRequest request) {
        UserEntity user = currentUserService.require(request);
        return Map.of("status", "ok", "knowledge_points", knowledgeService.listKps(user.getId()));
    }

    @GetMapping("/api/knowledge/kps/{id}")
    public Map<String, Object> getKp(HttpServletRequest request, @PathVariable Long id) {
        UserEntity user = currentUserService.require(request);
        return Map.of("status", "ok", "knowledge_point", knowledgeService.getKp(user.getId(), id));
    }

    @DeleteMapping("/api/knowledge/kps/{id}")
    public Map<String, Object> deleteKp(HttpServletRequest request, @PathVariable Long id) {
        UserEntity user = currentUserService.require(request);
        knowledgeService.deleteKp(user.getId(), id);
        return Map.of("status", "ok");
    }

    @GetMapping("/api/knowledge/search")
    public Map<String, Object> search(
            HttpServletRequest request,
            @RequestParam("q") String q,
            @RequestParam(value = "topic", required = false) String topic,
            @RequestParam(value = "limit", defaultValue = "5") int limit) {
        UserEntity user = currentUserService.require(request);
        Map<String, Object> result = knowledgeService.search(user.getId(), q, topic, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.putAll(result);
        return out;
    }

    @GetMapping("/api/knowledge/flashcards/due")
    public Map<String, Object> flashcardsDue(
            HttpServletRequest request, @RequestParam(value = "limit", defaultValue = "20") int limit) {
        UserEntity user = currentUserService.require(request);
        knowledgeService.requireEnabled();
        Map<String, Object> result = kpSrsService.dueToday(user.getId(), limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.putAll(result);
        return out;
    }

    @PostMapping("/api/knowledge/flashcards/{kpId}/review")
    public Map<String, Object> flashcardReview(
            HttpServletRequest request,
            @PathVariable Long kpId,
            @RequestBody Map<String, Object> body) {
        UserEntity user = currentUserService.require(request);
        knowledgeService.requireEnabled();
        String grade = body == null || body.get("grade") == null ? "good" : String.valueOf(body.get("grade"));
        Map<String, Object> card = kpSrsService.review(user.getId(), kpId, grade);
        return Map.of("status", "ok", "card", card);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
