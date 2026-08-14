package com.codearena.business.onboarding.web;

import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.domain.UserProfileEntity;
import com.codearena.business.user.domain.UserProfileRepository;
import com.codearena.business.user.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** First-run choices for a personal learning space. */
@RestController
@RequiredArgsConstructor
public class OnboardingController {

    private static final Set<String> GOALS = Set.of("algorithm", "backend", "frontend", "course", "system_design");
    private static final Set<String> STARTS = Set.of("path", "knowledge", "sync", "sample");

    private final CurrentUserService currentUserService;
    private final UserProfileRepository profileRepository;

    @GetMapping("/api/onboarding")
    public Map<String, Object> get(HttpServletRequest request) {
        UserEntity user = currentUserService.require(request);
        UserProfileEntity profile = profile(user.getId());
        return view(profile);
    }

    @PostMapping("/api/onboarding")
    @Transactional
    public ResponseEntity<Map<String, Object>> save(
            HttpServletRequest request, @RequestBody Map<String, Object> body) {
        UserEntity user = currentUserService.require(request);
        String goal = text(body, "learning_goal");
        String start = text(body, "start_mode");
        Integer minutes = number(body == null ? null : body.get("daily_minutes"));
        if (!GOALS.contains(goal) || !STARTS.contains(start) || minutes == null
                || !Set.of(15, 30, 60).contains(minutes)) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "请完成学习目标、时间和起点选择"));
        }
        UserProfileEntity profile = profile(user.getId());
        profile.setLearningGoal(goal);
        profile.setDailyMinutes(minutes);
        profile.setLearningStartMode(start);
        profile.setOnboardingCompleted(true);
        profileRepository.save(profile);
        return ResponseEntity.ok(view(profile));
    }

    private UserProfileEntity profile(Long userId) {
        return profileRepository.findById(userId).orElseGet(() -> {
            UserProfileEntity created = new UserProfileEntity();
            created.setUserId(userId);
            return profileRepository.save(created);
        });
    }

    private static Map<String, Object> view(UserProfileEntity profile) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("completed", Boolean.TRUE.equals(profile.getOnboardingCompleted()));
        body.put("learning_goal", profile.getLearningGoal());
        body.put("daily_minutes", profile.getDailyMinutes());
        body.put("start_mode", profile.getLearningStartMode());
        return body;
    }

    private static String text(Map<String, Object> body, String key) {
        return body == null || body.get(key) == null ? "" : String.valueOf(body.get(key)).trim();
    }

    private static Integer number(Object value) {
        if (value instanceof Number n) return n.intValue();
        try { return value == null ? null : Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ex) { return null; }
    }
}
