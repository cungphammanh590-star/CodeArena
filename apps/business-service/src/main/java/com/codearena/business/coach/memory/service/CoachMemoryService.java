package com.codearena.business.coach.memory.service;

import com.codearena.business.coach.memory.domain.UserCoachMemoryEntity;
import com.codearena.business.coach.memory.domain.UserCoachMemoryRepository;
import java.util.LinkedHashMap;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CoachMemoryService {

    private static final Set<String> KINDS = Set.of(
            UserCoachMemoryEntity.KIND_PREFERENCE,
            UserCoachMemoryEntity.KIND_WEAKNESS,
            UserCoachMemoryEntity.KIND_COACH_NOTE,
            UserCoachMemoryEntity.KIND_GOAL);

    private static final Set<String> SOURCES = Set.of(
            UserCoachMemoryEntity.SOURCE_USER,
            UserCoachMemoryEntity.SOURCE_COACH,
            UserCoachMemoryEntity.SOURCE_SYSTEM);

    private final UserCoachMemoryRepository memoryRepository;

    public List<UserCoachMemoryEntity> recall(Long userId, String kind, int limit) {
        return recall(userId, kind, null, limit);
    }

    public List<UserCoachMemoryEntity> recall(Long userId, String kind, Integer problemId, int limit) {
        int lim = Math.max(1, Math.min(20, limit));
        List<UserCoachMemoryEntity> rows;
        if (kind != null && !kind.isBlank()) {
            String k = normalizeKind(kind);
            rows = memoryRepository.findByUserIdAndKindAndActiveTrueOrderByUpdatedAtDesc(userId, k);
        } else {
            rows = memoryRepository.findByUserIdAndActiveTrueOrderByUpdatedAtDesc(userId);
        }
        OffsetDateTime now = OffsetDateTime.now();
        Comparator<UserCoachMemoryEntity> ranking = Comparator
                .comparingInt((UserCoachMemoryEntity row) ->
                        problemId != null && problemId.equals(row.getProblemId()) ? 1 : 0)
                .thenComparingInt(row -> UserCoachMemoryEntity.SOURCE_USER.equals(row.getSource()) ? 1 : 0)
                .thenComparing(row -> row.getConfidence() == null ? 0f : row.getConfidence())
                .thenComparing(row -> row.getLastConfirmedAt() == null
                        ? row.getUpdatedAt()
                        : row.getLastConfirmedAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                .reversed();
        return rows.stream()
                .filter(row -> row.getExpiresAt() == null || row.getExpiresAt().isAfter(now))
                .sorted(ranking)
                .limit(lim)
                .toList();
    }

    @Transactional
    public UserCoachMemoryEntity remember(
            Long userId,
            String kind,
            String content,
            String source,
            Integer problemId,
            Float confidence) {
        return remember(userId, kind, content, source, problemId, confidence, null, null, null, null);
    }

    @Transactional
    public UserCoachMemoryEntity remember(
            Long userId,
            String kind,
            String content,
            String source,
            Integer problemId,
            Float confidence,
            String memoryKey,
            String evidence,
            String sourceSessionId,
            Integer ttlDays) {
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content required");
        }
        String trimmed = content.trim();
        if (trimmed.length() > 2000) {
            trimmed = trimmed.substring(0, 2000);
        }
        String normalizedKind = normalizeKind(kind);
        String normalizedKey = normalizeMemoryKey(memoryKey);
        UserCoachMemoryEntity row = normalizedKey == null
                ? new UserCoachMemoryEntity()
                : memoryRepository
                        .findByUserIdAndKindAndMemoryKeyAndActiveTrue(userId, normalizedKind, normalizedKey)
                        .orElseGet(UserCoachMemoryEntity::new);
        boolean existing = row.getId() != null;
        row.setUserId(userId);
        row.setKind(normalizedKind);
        row.setContent(trimmed);
        row.setSource(normalizeSource(source));
        row.setProblemId(problemId != null && problemId > 0 ? problemId : null);
        row.setMemoryKey(normalizedKey);
        String normalizedEvidence = trim(evidence, 2000);
        if (normalizedEvidence != null) {
            row.setLastEvidence(normalizedEvidence);
        }
        String normalizedSessionId = trim(sourceSessionId, 64);
        if (normalizedSessionId != null) {
            row.setSourceSessionId(normalizedSessionId);
        }
        row.setLastConfirmedAt(OffsetDateTime.now());
        int priorEvidence = row.getEvidenceCount() == null ? 1 : Math.max(1, row.getEvidenceCount());
        row.setEvidenceCount(existing ? priorEvidence + 1 : 1);
        if (ttlDays != null) {
            int days = Math.max(1, Math.min(3650, ttlDays));
            row.setExpiresAt(OffsetDateTime.now().plusDays(days));
        }
        if (confidence != null) {
            row.setConfidence(Math.max(0f, Math.min(1f, confidence)));
        }
        row.setActive(true);
        return memoryRepository.save(row);
    }

    @Transactional
    public UserCoachMemoryEntity forget(Long userId, Long memoryId) {
        UserCoachMemoryEntity row = memoryRepository
                .findByIdAndUserId(memoryId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "memory not found"));
        row.setActive(false);
        return memoryRepository.save(row);
    }

    public Map<String, Object> toView(UserCoachMemoryEntity row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("kind", row.getKind());
        m.put("content", row.getContent());
        m.put("source", row.getSource());
        m.put("problem_id", row.getProblemId());
        m.put("memory_key", row.getMemoryKey());
        m.put("evidence_count", row.getEvidenceCount());
        m.put("last_evidence", row.getLastEvidence());
        m.put("source_session_id", row.getSourceSessionId());
        m.put("confidence", row.getConfidence());
        m.put("active", row.getActive());
        m.put("updated_at", row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
        m.put("last_confirmed_at", row.getLastConfirmedAt() == null ? null : row.getLastConfirmedAt().toString());
        m.put("expires_at", row.getExpiresAt() == null ? null : row.getExpiresAt().toString());
        return m;
    }

    private static String normalizeMemoryKey(String memoryKey) {
        if (memoryKey == null || memoryKey.isBlank()) {
            return null;
        }
        return trim(memoryKey.trim().toLowerCase(Locale.ROOT), 160);
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String normalizeKind(String kind) {
        String k = kind == null || kind.isBlank()
                ? UserCoachMemoryEntity.KIND_COACH_NOTE
                : kind.trim().toLowerCase(Locale.ROOT);
        if (!KINDS.contains(k)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "kind must be preference|weakness|coach_note|goal");
        }
        return k;
    }

    private static String normalizeSource(String source) {
        String s = source == null || source.isBlank()
                ? UserCoachMemoryEntity.SOURCE_COACH
                : source.trim().toLowerCase(Locale.ROOT);
        if (!SOURCES.contains(s)) {
            return UserCoachMemoryEntity.SOURCE_COACH;
        }
        return s;
    }
}
