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
 *   <li>{@code Authorization: Bearer ca_…}（扩展 / Web 登录）—— 携带但无效则 401，不静默落到 default
 *   <li>请求头 {@code X-User-Public-Id}（开发兼容）
 *   <li>否则种子用户 {@code default}
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class CurrentUserService {

    public static final String HEADER_PUBLIC_ID = "X-User-Public-Id";

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

    /** /api/auth/me：必须已登录（Bearer），不允许落到 default。 */
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
