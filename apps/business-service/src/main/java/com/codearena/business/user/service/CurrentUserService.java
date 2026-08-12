package com.codearena.business.user.service;

import com.codearena.business.user.domain.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 解析当前用户：
 *
 * <ol>
 *   <li>{@code Authorization: Bearer &lt;JWT&gt;} —— 验签 + 会话未吊销
 *   <li>Gateway 已鉴权注入：{@code X-CodeArena-Gateway-Auth=jwt} + {@code X-User-Public-Id}
 *   <li>否则忽略客户端伪造的 {@code X-User-Public-Id}，回退种子用户 {@code default}（仅本机直连开发）
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class CurrentUserService {

    public static final String HEADER_PUBLIC_ID = "X-User-Public-Id";
    public static final String HEADER_GATEWAY_AUTH = "X-CodeArena-Gateway-Auth";

    private final UserService userService;
    private final AuthService authService;

    public UserEntity require(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && !authorization.isBlank()) {
            UserEntity fromToken = authService.findUserByAccessToken(authorization);
            if (fromToken != null) {
                return fromToken;
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
        }

        String gatewayAuth = request.getHeader(HEADER_GATEWAY_AUTH);
        String publicId = request.getHeader(HEADER_PUBLIC_ID);
        if ("jwt".equalsIgnoreCase(gatewayAuth)
                && publicId != null
                && !publicId.isBlank()) {
            UserEntity user = userService.getByPublicId(publicId.trim());
            if (!UserEntity.STATUS_ACTIVE.equals(user.getStatus())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无法使用");
            }
            return user;
        }

        // 无 Bearer、无可信 Gateway 头时，不信任客户端 X-User-Public-Id
        return userService.ensureDefaultUser();
    }

    /** /api/auth/me：必须 JWT 有效且会话未吊销。 */
    public UserEntity requireSession(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        UserEntity fromToken = authService.findUserByAccessToken(authorization);
        if (fromToken == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        return fromToken;
    }
}
