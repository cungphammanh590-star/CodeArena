package com.codearena.business.learning.mastery.web;

import com.codearena.business.learning.mastery.domain.UserProblemFlagEntity;
import com.codearena.business.learning.mastery.domain.UserProblemFlagRepository;
import com.codearena.business.learning.srs.SrsService;
import com.codearena.business.shared.cache.UserStatsCacheService;
import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 题目掌握标记。 */
@RestController
@RequiredArgsConstructor
public class MasteryController {

    private final UserProblemFlagRepository userProblemFlagRepository;
    private final CurrentUserService currentUserService;
    private final UserStatsCacheService userStatsCacheService;
    private final SrsService srsService;

    @GetMapping("/api/mastered")
    public Map<String, Object> mastered(HttpServletRequest request) {
        UserEntity user = currentUserService.require(request);
        List<Map<String, Object>> items = new ArrayList<>();
        for (UserProblemFlagEntity flag :
                userProblemFlagRepository.findByUserIdAndMasteredTrue(user.getId())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("problem_id", flag.getProblemId());
            item.put("mastered", true);
            item.put(
                    "mastered_at",
                    flag.getMasteredAt() == null ? null : flag.getMasteredAt().toString());
            item.put("note", flag.getNote() == null ? "" : flag.getNote());
            items.add(item);
        }
        return Map.of("status", "ok", "items", items);
    }

    @PostMapping("/api/problems/{problemId}/mastered")
    @Transactional
    public Map<String, Object> setMastered(
            HttpServletRequest request,
            @PathVariable Integer problemId,
            @RequestBody(required = false) Map<String, Object> body) {
        UserEntity user = currentUserService.require(request);
        String note = body == null ? "" : String.valueOf(body.getOrDefault("note", ""));
        UserProblemFlagEntity flag = userProblemFlagRepository
                .findByUserIdAndProblemId(user.getId(), problemId)
                .orElseGet(UserProblemFlagEntity::new);
        flag.setUserId(user.getId());
        flag.setProblemId(problemId);
        flag.setMastered(true);
        flag.setMasteredAt(OffsetDateTime.now());
        flag.setNote(note);
        userProblemFlagRepository.save(flag);
        srsService.setSuspended(user.getId(), problemId, true);
        userStatsCacheService.invalidateUser(user.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("problem_id", problemId);
        result.put("mastered", true);
        result.put("note", note);
        return result;
    }

    @DeleteMapping("/api/problems/{problemId}/mastered")
    @Transactional
    public Map<String, Object> clearMastered(
            HttpServletRequest request, @PathVariable Integer problemId) {
        UserEntity user = currentUserService.require(request);
        UserProblemFlagEntity flag = userProblemFlagRepository
                .findByUserIdAndProblemId(user.getId(), problemId)
                .orElseGet(UserProblemFlagEntity::new);
        flag.setUserId(user.getId());
        flag.setProblemId(problemId);
        flag.setMastered(false);
        flag.setMasteredAt(null);
        userProblemFlagRepository.save(flag);
        srsService.setSuspended(user.getId(), problemId, false);
        userStatsCacheService.invalidateUser(user.getId());
        return Map.of("status", "ok", "problem_id", problemId, "mastered", false);
    }
}
