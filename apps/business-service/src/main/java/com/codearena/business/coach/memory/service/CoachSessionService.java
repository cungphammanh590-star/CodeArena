package com.codearena.business.coach.memory.service;

import com.codearena.business.coach.memory.domain.CoachSessionEntity;
import com.codearena.business.coach.memory.domain.CoachSessionRepository;
import com.codearena.business.coach.memory.domain.CoachTurnEntity;
import com.codearena.business.coach.memory.domain.CoachTurnRepository;
import com.codearena.business.user.domain.UserEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CoachSessionService {

    private final CoachSessionRepository sessionRepository;
    private final CoachTurnRepository turnRepository;

    @Transactional
    public CoachSessionEntity prepare(
            UserEntity user,
            String submissionId,
            Integer problemId,
            String mode,
            String topic) {
        String safeMode = (mode == null || mode.isBlank()) ? "default" : mode.trim();
        String sid = submissionId == null || submissionId.isBlank() ? null : submissionId.trim();
        String safeTopic = topic == null ? "" : topic.trim();

        if (!safeTopic.isBlank()) {
            Optional<CoachSessionEntity> existing =
                    sessionRepository.findFirstByUserIdAndTopicAndStatusOrderByUpdatedAtDesc(
                            user.getId(), safeTopic, CoachSessionEntity.STATUS_ACTIVE);
            if (existing.isPresent()) {
                return existing.get();
            }
        } else if (sid != null) {
            Optional<CoachSessionEntity> existing =
                    sessionRepository.findFirstByUserIdAndSubmissionIdAndStatusOrderByUpdatedAtDesc(
                            user.getId(), sid, CoachSessionEntity.STATUS_ACTIVE);
            if (existing.isPresent()) {
                return existing.get();
            }
        } else if (problemId != null && problemId > 0 && !isProfileMode(safeMode)) {
            Optional<CoachSessionEntity> existing =
                    sessionRepository.findFirstByUserIdAndProblemIdAndStatusOrderByUpdatedAtDesc(
                            user.getId(), problemId, CoachSessionEntity.STATUS_ACTIVE);
            if (existing.isPresent()) {
                return existing.get();
            }
        } else if ("lobby".equals(safeMode)
                || (("default".equals(safeMode) || safeMode.isBlank())
                        && (problemId == null || problemId <= 0)
                        && sid == null
                        && safeTopic.isBlank())) {
            Optional<CoachSessionEntity> existing =
                    sessionRepository.findFirstByUserIdAndSessionKindAndStatusOrderByUpdatedAtDesc(
                            user.getId(), "lobby", CoachSessionEntity.STATUS_ACTIVE);
            if (existing.isPresent()) {
                return existing.get();
            }
            safeMode = "lobby";
        }

        CoachSessionEntity session = new CoachSessionEntity();
        String sessionId = "biz-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        session.setSessionId(sessionId);
        session.setUserId(user.getId());
        session.setThreadId(sessionId);
        session.setSubmissionId(sid);
        session.setProblemId(problemId != null && problemId > 0 ? problemId : null);
        session.setMode(safeMode);
        session.setTopic(safeTopic);
        if (!safeTopic.isBlank()) {
            session.setSessionKind("topic");
            session.setPhase("today_brief");
        } else if (session.getProblemId() != null) {
            session.setSessionKind("problem");
            session.setPhase("in_problem");
        } else {
            session.setSessionKind("lobby");
            session.setPhase("lobby");
        }
        session.setStatus(CoachSessionEntity.STATUS_ACTIVE);
        session.setSummary("");
        session.setOpening(buildOpening(session));
        return sessionRepository.save(session);
    }

    public CoachSessionEntity requireOwned(String sessionId, Long userId) {
        return sessionRepository
                .findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found"));
    }

    /** stream 回调时若会话尚未 prepare，则按 session_id 惰性创建。 */
    @Transactional
    public CoachSessionEntity ensure(String sessionId, Long userId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "session_id required");
        }
        return sessionRepository
                .findBySessionIdAndUserId(sessionId, userId)
                .orElseGet(() -> {
                    CoachSessionEntity session = new CoachSessionEntity();
                    session.setSessionId(sessionId.trim());
                    session.setUserId(userId);
                    session.setThreadId(sessionId.trim());
                    session.setMode("default");
                    session.setPhase("lobby");
                    session.setStatus(CoachSessionEntity.STATUS_ACTIVE);
                    session.setOpening("");
                    return sessionRepository.save(session);
                });
    }

    public Optional<CoachSessionEntity> findReusable(
            UserEntity user, String submissionId, Integer problemId) {
        if (submissionId != null && !submissionId.isBlank()) {
            return sessionRepository.findFirstByUserIdAndSubmissionIdAndStatusOrderByUpdatedAtDesc(
                    user.getId(), submissionId.trim(), CoachSessionEntity.STATUS_ACTIVE);
        }
        if (problemId != null && problemId > 0) {
            return sessionRepository.findFirstByUserIdAndProblemIdAndStatusOrderByUpdatedAtDesc(
                    user.getId(), problemId, CoachSessionEntity.STATUS_ACTIVE);
        }
        return Optional.empty();
    }

    @Transactional
    public CoachSessionEntity bindProblem(String sessionId, Long userId, Integer problemId) {
        CoachSessionEntity session = ensure(sessionId, userId);
        session.setProblemId(problemId);
        session.setPhase("in_problem");
        if (session.getTopic() == null || session.getTopic().isBlank()) {
            session.setSessionKind("problem");
        }
        return sessionRepository.save(session);
    }

    @Transactional
    public CoachSessionEntity syncState(
            String sessionId,
            Long userId,
            String phase,
            Integer problemId,
            boolean close,
            String summary,
            String topic) {
        CoachSessionEntity session = ensure(sessionId, userId);
        if (phase != null && !phase.isBlank()) {
            session.setPhase(phase.trim());
        }
        if (problemId != null && problemId > 0) {
            session.setProblemId(problemId);
        }
        if (summary != null) {
            session.setSummary(summary);
        }
        if (topic != null && !topic.isBlank()) {
            session.setTopic(topic.trim());
            session.setSessionKind("topic");
        }
        if (close) {
            session.setStatus(CoachSessionEntity.STATUS_CLOSED);
            if (session.getPhase() == null || "lobby".equals(session.getPhase())) {
                session.setPhase("wrap");
            }
        }
        return sessionRepository.save(session);
    }

    @Transactional
    public CoachTurnEntity appendTurn(
            String sessionId,
            Long userId,
            String role,
            String content,
            String intent,
            String phase) {
        CoachSessionEntity session = ensure(sessionId, userId);
        CoachTurnEntity turn = new CoachTurnEntity();
        turn.setSessionId(session.getSessionId());
        turn.setUserId(userId);
        turn.setRole(role == null || role.isBlank() ? "assistant" : role.trim());
        turn.setContent(content == null ? "" : content);
        turn.setIntent(intent);
        turn.setPhase(phase != null && !phase.isBlank() ? phase : session.getPhase());
        CoachTurnEntity saved = turnRepository.save(turn);
        if (phase != null && !phase.isBlank()) {
            session.setPhase(phase.trim());
            sessionRepository.save(session);
        }
        return saved;
    }

    public List<CoachTurnEntity> listTurns(String sessionId, Long userId) {
        requireOwned(sessionId, userId);
        return turnRepository.findBySessionIdAndUserIdOrderByCreatedAtAsc(sessionId, userId);
    }

    public Map<String, Object> toView(CoachSessionEntity session) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_id", session.getSessionId());
        m.put("thread_id", session.getThreadId());
        m.put("problem_id", session.getProblemId());
        m.put("submission_id", session.getSubmissionId());
        m.put("mode", session.getMode());
        m.put("topic", session.getTopic());
        m.put("session_kind", session.getSessionKind());
        m.put("summary", session.getSummary());
        m.put("opening", session.getOpening());
        m.put("phase", session.getPhase());
        m.put("status", session.getStatus());
        m.put("created_at", session.getCreatedAt() == null ? null : session.getCreatedAt().toString());
        m.put("updated_at", session.getUpdatedAt() == null ? null : session.getUpdatedAt().toString());
        return m;
    }

    private static String buildOpening(CoachSessionEntity session) {
        if (session.getTopic() != null && !session.getTopic().isBlank()) {
            return "这轮我们围着「"
                    + session.getTopic()
                    + "」练。可以说说最近卡在哪，或直接报题号进单题。";
        }
        if (session.getProblemId() != null) {
            return "好，这题是 "
                    + session.getProblemId()
                    + "。卡住的地方直接说，也可以让我先看你最近一次提交，再一起拆思路。";
        }
        if (isProfileMode(session.getMode())) {
            return "我在。想听今日回顾可以说「今天怎么样」，想选题可以说「推荐一题」。";
        }
        return "我在陪练大厅。可以说目标（比如「准备 Google 面试 30 天」），"
                + "或直接报题号开始跟练。";
    }

    private static boolean isProfileMode(String mode) {
        return "daily_review".equals(mode) || "recommend".equals(mode) || "review".equals(mode);
    }
}
