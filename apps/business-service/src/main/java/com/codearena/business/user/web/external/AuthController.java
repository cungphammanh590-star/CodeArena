package com.codearena.business.user.web.external;

import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.service.AuthService;
import com.codearena.business.user.service.CurrentUserService;
import com.codearena.business.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 扩展 / Web 登录入口。Token 存 auth_sessions；CurrentUserService 优先读 Bearer。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CurrentUserService currentUserService;
    private final UserService userService;

    /** 扩展首选：设备 ID 静默登录（安装时生成 UUID）。 */
    @PostMapping("/device")
    public ResponseEntity<Map<String, Object>> device(@RequestBody Map<String, Object> body) {
        String deviceId = str(body.get("device_id"));
        String client = str(body.get("client"));
        if (client.isBlank()) {
            client = "extension";
        }
        return ResponseEntity.ok(authService.loginOrCreateDevice(deviceId, client));
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(authService.register(
                str(body.get("username")),
                str(body.get("password")),
                str(body.get("display_name"))));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(authService.login(
                str(body.get("username")),
                str(body.get("password")),
                str(body.get("client"))));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.logout(authorization);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        UserEntity user = currentUserService.requireSession(request);
        return ResponseEntity.ok(Map.of("status", "ok", "user", userService.toView(user)));
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
