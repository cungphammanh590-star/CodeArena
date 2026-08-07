package com.codearena.business.coach.memory.service;

import com.codearena.business.coach.memory.domain.UserCoachMemoryEntity;
import com.codearena.business.coach.memory.domain.UserCoachMemoryRepository;
import java.util.LinkedHashMap;
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
        int lim = Math.max(1, Math.min(20, limit));
        List<UserCoachMemoryEntity> rows;
        if (kind != null && !kind.isBlank()) {
            String k = normalizeKind(kind);
            rows = memoryRepository.findByUserIdAndKindAndActiveTrueOrderByUpdatedAtDesc(userId, k);
        } else {
            rows = memoryRepository.findByUserIdAndActiveTrueOrderByUpdatedAtDesc(userId);
        }
        if (rows.size() > lim) {
            return rows.subList(0, lim);
        }
        return rows;
    }

    @Transactional
    public UserCoachMemoryEntity remember(
            Long userId,
            String kind,
            String content,
            String source,
            Integer problemId,
            Float confidence) {
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content required");
        }
        String trimmed = content.trim();
        if (trimmed.length() > 2000) {
            trimmed = trimmed.substring(0, 2000);
        }
        UserCoachMemoryEntity row = new UserCoachMemoryEntity();
        row.setUserId(userId);
        row.setKind(normalizeKind(kind));
        row.setContent(trimmed);
        row.setSource(normalizeSource(source));
        row.setProblemId(problemId != null && problemId > 0 ? problemId : null);
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
        m.put("confidence", row.getConfidence());
        m.put("active", row.getActive());
        m.put("updated_at", row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
        return m;
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
