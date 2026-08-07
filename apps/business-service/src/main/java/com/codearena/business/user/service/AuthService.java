package com.codearena.business.user.service;

import com.codearena.business.user.domain.AuthSessionEntity;
import com.codearena.business.user.domain.AuthSessionRepository;
import com.codearena.business.user.domain.UserCredentialEntity;
import com.codearena.business.user.domain.UserCredentialRepository;
import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.domain.UserIdentityEntity;
import com.codearena.business.user.domain.UserIdentityRepository;
import com.codearena.business.user.domain.UserProfileEntity;
import com.codearena.business.user.domain.UserProfileRepository;
import com.codearena.business.user.domain.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {

    public static final String TOKEN_PREFIX = "ca_";

    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final UserIdentityRepository identityRepository;
    private final UserCredentialRepository credentialRepository;
    private final AuthSessionRepository sessionRepository;
    private final UserService userService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${codearena.auth.token-ttl-days:30}")
    private int tokenTtlDays;

    @Transactional
    public Map<String, Object> loginOrCreateDevice(String deviceId, String client) {
        String did = normalizeDeviceId(deviceId);
        UserIdentityEntity identity = identityRepository
                .findByProviderAndProviderUid(UserIdentityEntity.PROVIDER_DEVICE, did)
                .orElse(null);
        UserEntity user;
        if (identity == null) {
            user = createDeviceUser(did);
        } else {
            user = userService.getById(identity.getUserId());
        }
        return issueTokenResponse(user, client == null || client.isBlank() ? "extension" : client.trim());
    }

    @Transactional
    public Map<String, Object> register(String username, String password, String displayName) {
        if (password == null || password.length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少需要 6 位");
        }
        UserEntity user = userService.register(username, displayName, null);
        UserCredentialEntity cred = credentialRepository
                .findById(user.getId())
                .orElseGet(() -> {
                    UserCredentialEntity c = new UserCredentialEntity();
                    c.setUserId(user.getId());
                    return c;
                });
        cred.setPasswordHash(passwordEncoder.encode(password));
        credentialRepository.save(cred);
        return issueTokenResponse(user, "web");
    }

    @Transactional
    public Map<String, Object> login(String username, String password, String client) {
        if (username == null || username.isBlank() || password == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写用户名和密码");
        }
        String name = username.trim().toLowerCase();
        UserEntity user = userRepository
                .findByUsername(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码不正确"));
        UserCredentialEntity cred = credentialRepository
                .findById(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码不正确"));
        if (cred.getPasswordHash() == null
                || !passwordEncoder.matches(password, cred.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码不正确");
        }
        if (!UserEntity.STATUS_ACTIVE.equals(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无法使用");
        }
        return issueTokenResponse(user, client == null || client.isBlank() ? "web" : client.trim());
    }

    @Transactional
    public void logout(String rawToken) {
        String hash = hashToken(rawToken);
        sessionRepository
                .findByTokenHashAndRevokedAtIsNull(hash)
                .ifPresent(s -> {
                    s.setRevokedAt(OffsetDateTime.now());
                    sessionRepository.save(s);
                });
    }

    @Transactional(readOnly = true)
    public UserEntity findUserByAccessToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        String token = rawToken.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        }
        if (!token.startsWith(TOKEN_PREFIX)) {
            return null;
        }
        String hash = hashToken(token);
        AuthSessionEntity session = sessionRepository
                .findByTokenHashAndRevokedAtIsNull(hash)
                .orElse(null);
        if (session == null || !session.isActive()) {
            return null;
        }
        UserEntity user = userService.getById(session.getUserId());
        if (!UserEntity.STATUS_ACTIVE.equals(user.getStatus())) {
            return null;
        }
        return user;
    }

    private UserEntity createDeviceUser(String deviceId) {
        String username = "dev_" + deviceId.replace("-", "").substring(0, Math.min(12, deviceId.replace("-", "").length()));
        // 保证唯一
        String base = username;
        int i = 0;
        while (userRepository.existsByUsername(username)) {
            i++;
            username = base.substring(0, Math.min(24, base.length())) + i;
        }
        UserEntity user = new UserEntity();
        user.setPublicId(UserService.newPublicId());
        user.setUsername(username);
        user.setDisplayName("Extension User");
        user.setStatus(UserEntity.STATUS_ACTIVE);
        user = userRepository.save(user);

        UserProfileEntity profile = new UserProfileEntity();
        profile.setUserId(user.getId());
        profileRepository.save(profile);

        UserIdentityEntity local = new UserIdentityEntity();
        local.setUserId(user.getId());
        local.setProvider(UserIdentityEntity.PROVIDER_LOCAL);
        local.setProviderUid(username);
        identityRepository.save(local);

        UserIdentityEntity device = new UserIdentityEntity();
        device.setUserId(user.getId());
        device.setProvider(UserIdentityEntity.PROVIDER_DEVICE);
        device.setProviderUid(deviceId);
        identityRepository.save(device);

        UserCredentialEntity cred = new UserCredentialEntity();
        cred.setUserId(user.getId());
        credentialRepository.save(cred);
        return user;
    }

    private Map<String, Object> issueTokenResponse(UserEntity user, String client) {
        String raw = TOKEN_PREFIX + randomToken();
        AuthSessionEntity session = new AuthSessionEntity();
        session.setUserId(user.getId());
        session.setTokenHash(hashToken(raw));
        session.setClient(client);
        session.setExpiresAt(OffsetDateTime.now().plusDays(Math.max(1, tokenTtlDays)));
        sessionRepository.save(session);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("access_token", raw);
        body.put("token_type", "Bearer");
        body.put("expires_at", session.getExpiresAt().toString());
        body.put("user", userService.toView(user));
        return body;
    }

    private static String normalizeDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "device_id required");
        }
        String d = deviceId.trim();
        if (d.length() < 8 || d.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "device_id length 8-128");
        }
        if (!d.matches("^[A-Za-z0-9._:-]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "device_id invalid chars");
        }
        return d;
    }

    private String randomToken() {
        byte[] buf = new byte[24];
        secureRandom.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    static String hashToken(String rawToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException("sha-256 unavailable", e);
        }
    }
}
