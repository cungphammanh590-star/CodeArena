package com.codearena.business.user.service;

import com.codearena.business.user.api.UserLookup;
import com.codearena.business.user.domain.UserCredentialEntity;
import com.codearena.business.user.domain.UserCredentialRepository;
import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.domain.UserIdentityEntity;
import com.codearena.business.user.domain.UserIdentityRepository;
import com.codearena.business.user.domain.UserProfileEntity;
import com.codearena.business.user.domain.UserProfileRepository;
import com.codearena.business.user.domain.UserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class UserService implements UserLookup {

    public static final String DEFAULT_USERNAME = "default";

    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final UserIdentityRepository identityRepository;
    private final UserCredentialRepository credentialRepository;

    @Transactional(readOnly = true)
    public UserEntity getByPublicId(String publicId) {
        return userRepository
                .findByPublicId(publicId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    }

    @Transactional(readOnly = true)
    public UserEntity getById(Long id) {
        return userRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    }

    /** 保证 V1 种子用户存在；鉴权落地前作为隐式当前用户。 */
    @Transactional
    public UserEntity ensureDefaultUser() {
        return userRepository
                .findByUsername(DEFAULT_USERNAME)
                .orElseGet(() -> registerInternal(DEFAULT_USERNAME, "Default User", null));
    }

    @Transactional
    public UserEntity register(String username, String displayName, String email) {
        String name = normalizeUsername(username);
        if (DEFAULT_USERNAME.equals(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名不可用");
        }
        if (userRepository.existsByUsername(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该用户名已被使用，换一个试试");
        }
        if (email != null && !email.isBlank() && userRepository.findByEmail(email.trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该邮箱已被使用");
        }
        return registerInternal(name, displayName, email);
    }

    @Transactional
    public UserEntity updateProfile(
            UserEntity user, String displayName, String email, String bio, String locale, String timezone, String avatarUrl) {
        if (displayName != null) {
            user.setDisplayName(displayName.isBlank() ? null : displayName.trim());
        }
        if (email != null) {
            String e = email.isBlank() ? null : email.trim();
            if (e != null) {
                userRepository
                        .findByEmail(e)
                        .filter(other -> !other.getId().equals(user.getId()))
                        .ifPresent(other -> {
                            throw new ResponseStatusException(HttpStatus.CONFLICT, "email taken");
                        });
            }
            user.setEmail(e);
        }
        userRepository.save(user);

        UserProfileEntity profile = profileRepository
                .findById(user.getId())
                .orElseGet(() -> {
                    UserProfileEntity p = new UserProfileEntity();
                    p.setUserId(user.getId());
                    return p;
                });
        if (bio != null) {
            profile.setBio(bio.isBlank() ? null : bio);
        }
        if (locale != null && !locale.isBlank()) {
            profile.setLocale(locale.trim());
        }
        if (timezone != null && !timezone.isBlank()) {
            profile.setTimezone(timezone.trim());
        }
        if (avatarUrl != null) {
            profile.setAvatarUrl(avatarUrl.isBlank() ? null : avatarUrl.trim());
        }
        profileRepository.save(profile);
        return user;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> toView(UserEntity user) {
        UserProfileEntity profile = profileRepository.findById(user.getId()).orElse(null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("public_id", user.getPublicId());
        body.put("username", user.getUsername());
        body.put("display_name", user.getDisplayName());
        body.put("email", user.getEmail());
        body.put("status", user.getStatus());
        body.put("created_at", user.getCreatedAt() == null ? null : user.getCreatedAt().toString());
        body.put("updated_at", user.getUpdatedAt() == null ? null : user.getUpdatedAt().toString());
        Map<String, Object> profileView = new LinkedHashMap<>();
        if (profile != null) {
            profileView.put("avatar_url", profile.getAvatarUrl());
            profileView.put("bio", profile.getBio());
            profileView.put("locale", profile.getLocale());
            profileView.put("timezone", profile.getTimezone());
            profileView.put("onboarding_completed", Boolean.TRUE.equals(profile.getOnboardingCompleted()));
            profileView.put("learning_goal", profile.getLearningGoal());
            profileView.put("daily_minutes", profile.getDailyMinutes());
            profileView.put("learning_start_mode", profile.getLearningStartMode());
        } else {
            profileView.put("locale", "zh-CN");
            profileView.put("timezone", "Asia/Shanghai");
            profileView.put("onboarding_completed", false);
        }
        body.put("profile", profileView);
        return body;
    }

    private UserEntity registerInternal(String username, String displayName, String email) {
        UserEntity user = new UserEntity();
        user.setPublicId(newPublicId());
        user.setUsername(username);
        user.setDisplayName(
                displayName == null || displayName.isBlank() ? username : displayName.trim());
        if (email != null && !email.isBlank()) {
            user.setEmail(email.trim());
        }
        user.setStatus(UserEntity.STATUS_ACTIVE);
        user = userRepository.save(user);

        UserProfileEntity profile = new UserProfileEntity();
        profile.setUserId(user.getId());
        profileRepository.save(profile);

        UserIdentityEntity identity = new UserIdentityEntity();
        identity.setUserId(user.getId());
        identity.setProvider(UserIdentityEntity.PROVIDER_LOCAL);
        identity.setProviderUid(username);
        identityRepository.save(identity);

        UserCredentialEntity cred = new UserCredentialEntity();
        cred.setUserId(user.getId());
        credentialRepository.save(cred);

        return user;
    }

    public static String newPublicId() {
        return "usr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写用户名");
        }
        String name = username.trim().toLowerCase();
        if (!name.matches("^[a-z0-9_]{3,32}$")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "用户名需为 3–32 位小写字母、数字或下划线");
        }
        return name;
    }
}
