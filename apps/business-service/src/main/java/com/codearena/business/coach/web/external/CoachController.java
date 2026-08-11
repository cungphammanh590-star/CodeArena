package com.codearena.business.coach.web.external;

import com.codearena.business.coach.memory.domain.CoachSessionEntity;
import com.codearena.business.coach.memory.domain.CoachTurnEntity;
import com.codearena.business.coach.memory.service.CoachSessionService;
import com.codearena.business.problem.domain.ProblemEntity;
import com.codearena.business.problem.domain.ProblemRepository;
import com.codearena.business.submission.domain.SubmissionEntity;
import com.codearena.business.submission.domain.SubmissionRepository;
import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 陪练业务编排（Java）。对话 SSE 在 llm-service；此处负责会话/开场/提示与上下文。
 */
@RestController
@RequiredArgsConstructor
public class CoachController {

    private final CurrentUserService currentUserService;
    private final CoachSessionService sessionService;
    private final SubmissionRepository submissionRepository;
    private final ProblemRepository problemRepository;

    @GetMapping({"/api/coach/hint", "/api/coach/hint/{pathProblemId}"})
    public ResponseEntity<Map<String, Object>> hint(
            HttpServletRequest request,
            @PathVariable(required = false) Integer pathProblemId,
            @RequestParam(required = false) Integer problem_id,
            @RequestParam(required = false) String slug) {
        UserEntity user = currentUserService.require(request);
        Integer pid = pathProblemId != null ? pathProblemId : problem_id;
        if (pid == null && (slug == null || slug.isBlank())) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "status",
                            "error",
                            "message",
                            "需要 problem_id 或已在库中的 slug；先在题目页提交一次可自动同步题号"));
        }

        Integer resolvedPid = pid;
        if (resolvedPid == null) {
            final String slugKey = slug.trim();
            resolvedPid = problemRepository.findAll().stream()
                    .filter(p -> slugKey.equalsIgnoreCase(p.getSlug()))
                    .map(ProblemEntity::getProblemId)
                    .findFirst()
                    .orElse(null);
        }
        if (resolvedPid == null || resolvedPid <= 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "无法解析题号"));
        }

        final int problemIdKey = resolvedPid;
        Optional<SubmissionEntity> latest = submissionRepository
                .findFirstByProblemIdAndUserIdOrderBySubmittedAtDesc(problemIdKey, user.getId())
                .or(() -> submissionRepository.findFirstByProblemIdOrderBySubmittedAtDesc(problemIdKey));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("problem_id", problemIdKey);
        body.put("slug", slug == null || slug.isBlank() ? null : slug.trim());
        body.put("owner", "business-service");
        if (latest.isPresent()) {
            SubmissionEntity sub = latest.get();
            body.put("latest_submission_id", sub.getSubmissionId());
            body.put("latest_status", sub.getStatus());
            body.put(
                    "suggestion",
                    "已关联本题最近提交（"
                            + (sub.getStatus() == null ? "未知状态" : sub.getStatus())
                            + "）。可以直接提问或点「看思路」。");
            body.put(
                    "hint",
                    "题目 "
                            + problemIdKey
                            + " 最近提交 "
                            + sub.getSubmissionId()
                            + " · "
                            + (sub.getStatus() == null ? "?" : sub.getStatus()));
        } else {
            body.put("latest_submission_id", null);
            body.put(
                    "suggestion",
                    "本题在本系统还没有同步到的提交记录。仍可直接开聊；"
                            + "在力扣提交且扩展同步成功后，会自动关联代码与状态。");
            body.put("hint", "题目 " + problemIdKey + " 暂无本地提交；可先讨论题意与思路。");
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/coach/session")
    public ResponseEntity<Map<String, Object>> session(
            HttpServletRequest request,
            @RequestParam(required = false) String submission_id,
            @RequestParam(required = false) String submission,
            @RequestParam(required = false) Integer problem_id,
            @RequestParam(required = false) String session_id) {
        UserEntity user = currentUserService.require(request);
        String sid = firstNonBlank(submission_id, submission);
        if (!isBlank(session_id)) {
            CoachSessionEntity session = sessionService.requireOwned(session_id.trim(), user.getId());
            return ResponseEntity.ok(sessionBody(session, user, true));
        }
        if ((sid == null || sid.isBlank()) && problem_id == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "status",
                            "error",
                            "message",
                            "submission_id or problem_id or session_id required"));
        }
        return sessionService
                .findReusable(user, sid, problem_id)
                .map(s -> ResponseEntity.ok(sessionBody(s, user, true)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of(
                                "status",
                                "error",
                                "message",
                                "session not found",
                                "owner",
                                "business-service")));
    }

    @PostMapping("/api/coach/prepare")
    public ResponseEntity<Map<String, Object>> prepare(
            HttpServletRequest request, @RequestBody(required = false) Map<String, Object> payload) {
        UserEntity user = currentUserService.require(request);
        Map<String, Object> bodyIn = payload == null ? Map.of() : payload;
        String submissionId = str(bodyIn.get("submission_id"));
        String mode = str(bodyIn.get("mode"));
        String topic = str(bodyIn.get("topic"));
        Integer problemId = toInt(bodyIn.get("problem_id"));

        if (isBlank(mode)
                && isBlank(submissionId)
                && problemId == null
                && isBlank(topic)) {
            mode = "lobby";
        }

        if (!isProfileMode(mode)
                && !isLobbyMode(mode)
                && isBlank(submissionId)
                && problemId == null
                && isBlank(topic)) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "status",
                            "error",
                            "message",
                            "submission_id or problem_id or mode or topic required"));
        }

        CoachSessionEntity session = sessionService.prepare(
                user,
                isBlank(submissionId) ? null : submissionId,
                problemId,
                isBlank(mode) ? "default" : mode,
                isBlank(topic) ? null : topic);
        Map<String, Object> body = sessionBody(session, user, false);
        body.put("owner", "business-service");
        body.put("stream_path", "/api/coach/stream");
        body.put("opening_source", "template");
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> sessionBody(CoachSessionEntity session, UserEntity user, boolean withTurns) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.putAll(sessionService.toView(session));
        body.put("user_public_id", user.getPublicId());
        if (withTurns) {
            List<Map<String, Object>> turns = sessionService.listTurns(session.getSessionId(), user.getId()).stream()
                    .map(this::turnView)
                    .toList();
            body.put("turns", turns);
        }
        return body;
    }

    private Map<String, Object> turnView(CoachTurnEntity t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("role", t.getRole());
        m.put("content", t.getContent());
        m.put("intent", t.getIntent());
        m.put("phase", t.getPhase());
        m.put("created_at", t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
        return m;
    }

    private static boolean isProfileMode(String mode) {
        return "daily_review".equals(mode) || "recommend".equals(mode) || "review".equals(mode);
    }

    private static boolean isLobbyMode(String mode) {
        return "lobby".equals(mode) || "default".equals(mode);
    }

    private static String firstNonBlank(String a, String b) {
        if (!isBlank(a)) {
            return a;
        }
        if (!isBlank(b)) {
            return b;
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static Integer toInt(Object o) {
        if (o == null || "".equals(o)) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(o));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
