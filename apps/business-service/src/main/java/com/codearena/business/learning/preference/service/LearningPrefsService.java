package com.codearena.business.learning.preference.service;

import com.codearena.business.learning.list.domain.ProblemListItemEntity;
import com.codearena.business.learning.list.domain.ProblemListItemRepository;
import com.codearena.business.learning.mastery.domain.UserProblemFlagEntity;
import com.codearena.business.learning.mastery.domain.UserProblemFlagRepository;
import com.codearena.business.learning.preference.domain.LearningPrefsEntity;
import com.codearena.business.learning.preference.domain.LearningPrefsRepository;
import com.codearena.business.submission.domain.SubmissionRepository;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 按用户读写学习偏好，并计算活跃题单进度。 */
@Service
@RequiredArgsConstructor
public class LearningPrefsService {

    private final LearningPrefsRepository learningPrefsRepository;
    private final ProblemListItemRepository problemListItemRepository;
    private final SubmissionRepository submissionRepository;
    private final UserProblemFlagRepository userProblemFlagRepository;

    public LearningPrefsEntity getOrCreate(Long userId) {
        return learningPrefsRepository
                .findFirstByUserIdOrderByIdAsc(userId)
                .orElseGet(() -> {
                    LearningPrefsEntity created = new LearningPrefsEntity();
                    created.setUserId(userId);
                    created.setListMode(true);
                    created.setKgMode(true);
                    created.setActiveListId("hot100");
                    created.setUpdatedAt(OffsetDateTime.now());
                    return learningPrefsRepository.save(created);
                });
    }

    public Map<String, Object> toLearningMap(LearningPrefsEntity prefs) {
        Map<String, Object> learning = new LinkedHashMap<>();
        learning.put("list_mode", Boolean.TRUE.equals(prefs.getListMode()));
        learning.put("kg_mode", Boolean.TRUE.equals(prefs.getKgMode()));
        learning.put(
                "active_list_id",
                prefs.getActiveListId() == null || prefs.getActiveListId().isBlank()
                        ? "hot100"
                        : prefs.getActiveListId());
        return learning;
    }

    @Transactional
    public LearningPrefsEntity update(
            Long userId, Boolean listMode, Boolean kgMode, String activeListId) {
        LearningPrefsEntity prefs = getOrCreate(userId);
        if (listMode != null) {
            prefs.setListMode(listMode);
        }
        if (kgMode != null) {
            prefs.setKgMode(kgMode);
        }
        if (activeListId != null) {
            String aid = activeListId.trim();
            prefs.setActiveListId(aid.isEmpty() ? "hot100" : aid);
        }
        prefs.setUserId(userId);
        prefs.setUpdatedAt(OffsetDateTime.now());
        return learningPrefsRepository.save(prefs);
    }

    @Transactional
    public Map<String, Object> setActiveList(Long userId, String listId) {
        LearningPrefsEntity prefs = update(userId, null, null, listId);
        return toLearningMap(prefs);
    }

    /**
     * 题单进度：同时返回 snake_case 与前端兼容字段。
     *
     * <ul>
     *   <li>{@code list_total}/{@code total} — 题单题数
     *   <li>{@code list_done}/{@code done} — 至少一次 Accepted 的题数
     *   <li>{@code list_mastered}/{@code mastered_count} — 标记已掌握且在题单内的题数
     * </ul>
     */
    public Map<String, Object> computeListProgress(Long userId, String listId) {
        String active = listId == null || listId.isBlank() ? "hot100" : listId.trim();
        List<ProblemListItemEntity> items =
                problemListItemRepository.findByListIdOrderBySortOrderAsc(active);
        Set<Integer> problemIds =
                items.stream().map(ProblemListItemEntity::getProblemId).collect(Collectors.toSet());

        long total = problemIds.size();
        long done = 0;
        if (!problemIds.isEmpty()) {
            List<Integer> accepted =
                    submissionRepository.findDistinctProblemIdsByUserIdAndStatusAndProblemIdIn(
                            userId, "Accepted", problemIds);
            done = accepted.size();
        }

        long mastered = 0;
        if (!problemIds.isEmpty()) {
            Set<Integer> idSet = new HashSet<>(problemIds);
            for (UserProblemFlagEntity flag :
                    userProblemFlagRepository.findByUserIdAndMasteredTrue(userId)) {
                if (idSet.contains(flag.getProblemId())) {
                    mastered += 1;
                }
            }
        }

        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("list_total", total);
        progress.put("list_done", done);
        progress.put("list_mastered", mastered);
        progress.put("total", total);
        progress.put("done", done);
        progress.put("mastered_count", mastered);
        progress.put("kg_total", 0);
        progress.put("kg_done", 0);
        progress.put("active_list_id", active);
        return progress;
    }
}
