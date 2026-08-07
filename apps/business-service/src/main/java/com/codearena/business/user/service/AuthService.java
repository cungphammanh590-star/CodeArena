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
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final UserIdentityRepository identityRepository;
    private final UserCredentialRepository credentialRepository;
    private final AuthSessionRepository sessionRepository;
    private final UserService userService;
    private final JwtTokenService jwtTokenService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

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
        try {
            Claims claims = jwtTokenService.parse(rawToken);
            String jti = claims.getId();
            if (jti == null || jti.isBlank()) {
                return;
            }
            sessionRepository
                    .findByTokenHashAndRevokedAtIsNull(hashToken(jti))
                    .ifPresent(s -> {
                        s.setRevokedAt(OffsetDateTime.now());
                        sessionRepository.save(s);
                    });
        } catch (JwtException | IllegalArgumentException ignored) {
            // 无效 token：视为已登出
        }
    }

    @Transactional(readOnly = true)
    public UserEntity findUserByAccessToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        try {
            Claims claims = jwtTokenService.parse(rawToken);
            String jti = claims.getId();
            if (jti == null || jti.isBlank()) {
                return null;
            }
            AuthSessionEntity session = sessionRepository
                    .findByTokenHashAndRevokedAtIsNull(hashToken(jti))
                    .orElse(null);
            if (session == null || !session.isActive()) {
                return null;
            }
            UserEntity user = userService.getById(session.getUserId());
            if (!UserEntity.STATUS_ACTIVE.equals(user.getStatus())) {
                return null;
            }
            // 防篡改：JWT sub 必须与会话用户一致
            String sub = claims.getSubject();
            if (sub != null && !sub.equals(user.getPublicId())) {
                return null;
            }
            return user;
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    private UserEntity createDeviceUser(String deviceId) {
        String username = "dev_" + deviceId.replace("-", "").substring(0, Math.min(12, deviceId.replace("-", "").length()));
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
        JwtTokenService.IssuedJwt issued = jwtTokenService.issue(
                user.getPublicId(),
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                client);

        AuthSessionEntity session = new AuthSessionEntity();
        session.setUserId(user.getId());
        session.setTokenHash(hashToken(issued.jti()));
        session.setClient(client);
        session.setExpiresAt(issued.expiresAt());
        sessionRepository.save(session);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("access_token", issued.token());
        body.put("token_type", "Bearer");
        body.put("token_format", "jwt");
        body.put("expires_at", issued.expiresAt().toString());
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
