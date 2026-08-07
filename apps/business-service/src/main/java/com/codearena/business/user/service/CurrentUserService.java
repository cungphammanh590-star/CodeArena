package com.codearena.business.user.service;

import com.codearena.business.user.domain.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 解析当前用户（Gateway 已校验 JWT 时会注入可信头）：
 *
 * <ol>
 *   <li>{@code Authorization: Bearer &lt;JWT&gt;} —— 验签 + 会话未吊销
 *   <li>Gateway 注入的 {@code X-User-Public-Id}（仅当同时带有效 Bearer 时信任，防伪造）
 *   <li>开发兼容：无 Bearer 时的 {@code X-User-Public-Id}
 *   <li>否则种子用户 {@code default}
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

        // Gateway 已鉴权时注入；无 Bearer 的纯头仍作开发兼容
        String publicId = request.getHeader(HEADER_PUBLIC_ID);
        if (publicId != null && !publicId.isBlank()) {
            UserEntity user = userService.getByPublicId(publicId.trim());
            if (!UserEntity.STATUS_ACTIVE.equals(user.getStatus())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无法使用");
            }
            return user;
        }
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
