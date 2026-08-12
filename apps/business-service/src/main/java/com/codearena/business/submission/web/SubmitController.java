package com.codearena.business.submission.web;

import com.codearena.business.learning.srs.SrsService;
import com.codearena.business.problem.domain.ProblemEntity;
import com.codearena.business.submission.domain.SubmissionEntity;
import com.codearena.business.problem.domain.ProblemRepository;
import com.codearena.business.submission.domain.SubmissionRepository;
import com.codearena.business.shared.cache.UserStatsCacheService;
import com.codearena.business.user.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SubmitController {

    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;
    private final CurrentUserService currentUserService;
    private final UserStatsCacheService userStatsCacheService;
    private final SrsService srsService;

    @PostMapping("/submit")
    @Transactional
    public ResponseEntity<Map<String, Object>> submit(
            HttpServletRequest request, @RequestBody Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "JSON body must be an object");
        }

        Object sidRaw = payload.get("submission_id");
        if (sidRaw == null) {
            sidRaw = payload.get("id");
        }
        if (sidRaw == null) {
            return error(HttpStatus.BAD_REQUEST, "submission_id required");
        }
        String submissionId = String.valueOf(sidRaw);

        var currentUser = currentUserService.require(request);

        Optional<SubmissionEntity> existing = submissionRepository.findBySubmissionId(submissionId);
        if (existing.isPresent()) {
            SubmissionEntity row = existing.get();
            // 认领到当前登录用户（扩展曾因未登录/网络失败导致行挂在其他账号或空 user）
            if (row.getUserId() == null || !row.getUserId().equals(currentUser.getId())) {
                row.setUserId(currentUser.getId());
                if (payload.get("status") != null) {
                    row.setStatus(String.valueOf(payload.get("status")));
                }
                if (payload.get("code") != null) {
                    row.setCode(String.valueOf(payload.get("code")));
                }
                submissionRepository.save(row);
                userStatsCacheService.invalidateUser(currentUser.getId());
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "success");
            body.put("message", "Submission already exists");
            body.put("submission_id", submissionId);
            body.put("created", false);
            return ResponseEntity.ok(body);
        }

        Integer problemId = toInt(payload.get("problem_id"));
        if (problemId == null) {
            return error(HttpStatus.BAD_REQUEST, "problem_id required");
        }

        upsertProblem(problemId, payload);

        SubmissionEntity entity = new SubmissionEntity();
        entity.setSubmissionId(submissionId);
        entity.setProblemId(problemId);
        entity.setStatus(String.valueOf(payload.getOrDefault("status", "Unknown")));
        entity.setCode(payload.get("code") == null ? null : String.valueOf(payload.get("code")));
        entity.setRuntimeMs(toInt(payload.get("runtime_ms")));
        entity.setMemoryMb(toDouble(payload.get("memory_mb")));
        entity.setLanguage(
                payload.get("language") == null ? null : String.valueOf(payload.get("language")));
        entity.setUserId(currentUser.getId());
        entity.setSubmittedAt(OffsetDateTime.now());
        submissionRepository.save(entity);
        srsService.recordSubmission(currentUser.getId(), problemId, entity.getStatus());
        userStatsCacheService.invalidateUser(currentUser.getId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("message", "Submission saved");
        body.put("submission_id", submissionId);
        body.put("created", true);
        return ResponseEntity.ok(body);
    }

    private void upsertProblem(Integer problemId, Map<String, Object> payload) {
        ProblemEntity problem = problemRepository
                .findByProblemId(problemId)
                .orElseGet(ProblemEntity::new);
        problem.setProblemId(problemId);
        problem.setTitle(String.valueOf(payload.getOrDefault(
                "title", problem.getTitle() != null ? problem.getTitle() : "Problem " + problemId)));
        problem.setSlug(String.valueOf(payload.getOrDefault(
                "slug",
                problem.getSlug() != null ? problem.getSlug() : "problem-" + problemId)));
        Object difficulty = payload.get("difficulty");
        if (difficulty != null) {
            problem.setDifficulty(String.valueOf(difficulty));
        }
        Object tags = payload.get("tags");
        if (tags != null) {
            problem.setTags(String.valueOf(tags));
        }
        if (problem.getCreatedAt() == null) {
            problem.setCreatedAt(OffsetDateTime.now());
        }
        problemRepository.save(problem);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
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

    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
