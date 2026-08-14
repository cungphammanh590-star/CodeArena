package com.codearena.business.learning.web;

import com.codearena.business.knowledge.domain.KbDocumentRepository;
import com.codearena.business.knowledge.domain.KbKnowledgePointRepository;
import com.codearena.business.learning.srs.domain.UserProblemSrsRepository;
import com.codearena.business.submission.domain.SubmissionEntity;
import com.codearena.business.submission.domain.SubmissionRepository;
import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.service.CurrentUserService;
import com.codearena.business.coach.memory.domain.CoachSessionRepository;
import com.codearena.business.coach.memory.domain.CoachSessionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.mail.internet.MimeMessage;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.HashSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only personal learning archive, including sync health and a portable export. */
@RestController
@RequiredArgsConstructor
public class LearningArchiveController {
    private final CurrentUserService currentUserService;
    private final SubmissionRepository submissionRepository;
    private final UserProblemSrsRepository srsRepository;
    private final KbDocumentRepository documentRepository;
    private final KbKnowledgePointRepository knowledgePointRepository;
    private final CoachSessionRepository coachSessionRepository;
    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    @Value("${codearena.mail.enabled:false}") private boolean mailEnabled;
    @Value("${codearena.mail.from:no-reply@codearena.local}") private String mailFrom;

    @GetMapping("/api/learning/archive")
    public Map<String, Object> archive(HttpServletRequest request) {
        UserEntity user = currentUserService.require(request);
        List<SubmissionEntity> recent = submissionRepository.findTop80ByUserIdOrderBySubmittedAtDesc(user.getId());
        long accepted = submissionRepository.countByUserIdAndStatus(user.getId(), "Accepted");
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(7);
        long week = submissionRepository.findByUserIdAndSubmittedAtGreaterThanEqualOrderBySubmittedAtDesc(user.getId(), since).size();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("last_submission_at", recent.isEmpty() || recent.get(0).getSubmittedAt() == null
                ? null : recent.get(0).getSubmittedAt().toString());
        body.put("summary", Map.of("submission_count", submissionRepository.countByUserId(user.getId()), "accepted_count", accepted, "week_activity", week, "problem_review_count", srsRepository.countByUserId(user.getId()), "knowledge_document_count", documentRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).size(), "knowledge_point_count", knowledgePointRepository.findByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), "ready").size()));
        body.put("repeated_error_problem_ids", repeatedErrorProblemIds(recent));
        List<CoachSessionEntity> sessions = coachSessionRepository.findTop20ByUserIdOrderByUpdatedAtDesc(user.getId());
        body.put("latest_nex", sessions.isEmpty() ? null : nexSummary(sessions.get(0)));
        body.put("mail_enabled", mailEnabled && user.getEmail() != null && !user.getEmail().isBlank());
        return body;
    }

    @GetMapping("/api/learning/week-report")
    public Map<String,Object> weekReport(HttpServletRequest request, @RequestParam(required = false) String week) {
        UserEntity user = currentUserService.require(request);
        LocalDate selected = week == null || week.isBlank() ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : LocalDate.parse(week);
        LocalDate start = selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = start.plusDays(7);
        var zone = ZoneId.of("Asia/Shanghai");
        var items = submissionRepository.findByUserIdAndSubmittedAtGreaterThanEqualAndSubmittedAtLessThanOrderBySubmittedAtDesc(user.getId(), start.atStartOfDay(zone).toOffsetDateTime(), end.atStartOfDay(zone).toOffsetDateTime());
        var acceptedProblems = new HashSet<Integer>(); var failures = new HashMap<Integer,Integer>();
        items.forEach(s -> { if ("Accepted".equals(s.getStatus())) acceptedProblems.add(s.getProblemId()); else failures.merge(s.getProblemId(), 1, Integer::sum); });
        long repeated = failures.values().stream().filter(n -> n >= 2).count();
        long knowledgeAdded = knowledgePointRepository.findByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), "ready").stream().filter(k -> k.getCreatedAt() != null && !k.getCreatedAt().isBefore(start.atStartOfDay(zone).toOffsetDateTime()) && k.getCreatedAt().isBefore(end.atStartOfDay(zone).toOffsetDateTime())).count();
        String suggestion = repeated > 0 ? "下周先复盘重复出错的题，再进入新内容。" : items.isEmpty() ? "从一个 15 分钟的小任务开始，建立本周学习记录。" : "保持当前节奏，并安排一次间隔复习。";
        return Map.of("status","ok","week_start",start.toString(),"week_end",end.minusDays(1).toString(),"submission_count",items.size(),"accepted_problem_count",acceptedProblems.size(),"repeated_error_problem_count",repeated,"knowledge_added",knowledgeAdded,"review_queue_count",srsRepository.countByUserId(user.getId()),"suggestion",suggestion);
    }

    @GetMapping("/api/learning/export")
    public Map<String, Object> export(HttpServletRequest request) {
        UserEntity user = currentUserService.require(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("format", "codearena-learning-export/v1");
        body.put("exported_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        body.put("profile", Map.of("public_id", user.getPublicId(), "username", user.getUsername(), "display_name", String.valueOf(user.getDisplayName())));
        Map<String, Object> datasets = new LinkedHashMap<>();
        for (String table : List.of(
                "user_profiles", "user_identities", "learning_prefs", "submissions",
                "user_problem_flags", "user_problem_srs", "user_kp_mastery", "user_kp_srs",
                "study_plans", "plan_notifications", "coach_sessions", "coach_turns",
                "user_coach_memories", "coach_code_runs", "kb_documents", "kb_knowledge_points")) {
            datasets.put(table, jdbc.queryForList("SELECT * FROM " + table + " WHERE user_id = ?", user.getId()));
        }
        datasets.put("plan_daily_tasks", jdbc.queryForList(
                "SELECT task.* FROM plan_daily_tasks task JOIN study_plans plan ON plan.id = task.plan_id WHERE plan.user_id = ?",
                user.getId()));
        datasets.put("kb_embeddings", jdbc.queryForList(
                "SELECT emb.* FROM kb_embeddings emb JOIN kb_knowledge_points kp ON kp.id = emb.knowledge_point_id WHERE kp.user_id = ?",
                user.getId()));
        // Model keys and session token hashes are deliberately excluded from every export.
        body.put("datasets", datasets);
        return body;
    }

    @PostMapping("/api/learning/export/email")
    public ResponseEntity<?> emailExport(HttpServletRequest request) {
        UserEntity user = currentUserService.requireSession(request);
        if (!mailEnabled) return ResponseEntity.status(503).body(Map.of("status","error","message","邮件服务尚未启用"));
        if (user.getEmail() == null || user.getEmail().isBlank()) return ResponseEntity.badRequest().body(Map.of("status","error","message","请先在账号资料中填写邮箱"));
        try {
            byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(export(request));
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailFrom); helper.setTo(user.getEmail()); helper.setSubject("CodeArena 学习数据导出");
            helper.setText("附件是你的 CodeArena 学习数据。请妥善保存。", false);
            helper.addAttachment("codearena-learning-export.json", new ByteArrayResource(json));
            mailSender.send(message);
            return ResponseEntity.ok(Map.of("status","ok","sent_to",maskEmail(user.getEmail())));
        } catch (Exception ex) {
            return ResponseEntity.status(502).body(Map.of("status","error","message","邮件发送失败，请稍后再试"));
        }
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@'); if (at <= 1) return "***" + email.substring(Math.max(0, at));
        return email.substring(0, 1) + "***" + email.substring(at);
    }

    private static List<Integer> repeatedErrorProblemIds(List<SubmissionEntity> submissions) {
        Map<Integer, Integer> failures = new LinkedHashMap<>();
        submissions.stream()
                .filter(item -> !"Accepted".equals(item.getStatus()))
                .forEach(item -> failures.merge(item.getProblemId(), 1, Integer::sum));
        return failures.entrySet().stream()
                .filter(entry -> entry.getValue() >= 2)
                .map(Map.Entry::getKey)
                .limit(5)
                .toList();
    }

    private static Map<String, Object> nexSummary(CoachSessionEntity session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String goal = session.getTopic();
        if (goal == null || goal.isBlank()) {
            goal = session.getProblemId() == null ? "通用学习陪练" : "题目 " + session.getProblemId();
        }
        result.put("session_id", session.getSessionId());
        result.put("goal", goal);
        result.put("mastered", session.getSummary() == null ? "" : session.getSummary());
        result.put("remaining", CoachSessionEntity.STATUS_CLOSED.equals(session.getStatus()) ? "" : "本轮尚未结束，可继续与 Nex 学习");
        result.put("suggested_review_at", session.getUpdatedAt() == null ? null : session.getUpdatedAt().plusDays(3).toString());
        result.put("updated_at", session.getUpdatedAt() == null ? null : session.getUpdatedAt().toString());
        return result;
    }
}
