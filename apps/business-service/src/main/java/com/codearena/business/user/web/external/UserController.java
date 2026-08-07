package com.codearena.business.user.web.external;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.codearena.business.user.domain.UserEntity;
import com.codearena.business.user.domain.UserRepository;
import com.codearena.business.user.service.CurrentUserService;
import com.codearena.business.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Validated
public class UserController {

    private final UserService userService;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "domain", "user",
                "deployable", "business-service",
                "auth", "header:" + CurrentUserService.HEADER_PUBLIC_ID + "|default-user");
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        UserEntity user = currentUserService.require(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("user", userService.toView(user));
        return body;
    }

    @PatchMapping("/me")
    public Map<String, Object> patchMe(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        UserEntity user = currentUserService.require(request);
        user = userService.updateProfile(
                user,
                str(body, "display_name"),
                str(body, "email"),
                str(body, "bio"),
                str(body, "locale"),
                str(body, "timezone"),
                str(body, "avatar_url"));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("user", userService.toView(user));
        return resp;
    }

    @GetMapping("/{publicId}")
    public Map<String, Object> getByPublicId(@PathVariable String publicId) {
        UserEntity user = userService.getByPublicId(publicId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("user", userService.toView(user));
        return body;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest req) {
        UserEntity user =
                userService.register(req.username(), req.displayName(), req.email());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("user", userService.toView(user));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        var result = userRepository.findAll(PageRequest.of(safePage, safeSize));
        List<Map<String, Object>> items =
                result.getContent().stream().map(userService::toView).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("items", items);
        body.put("page", safePage);
        body.put("size", safeSize);
        body.put("total", result.getTotalElements());
        return body;
    }

    public record RegisterRequest(
            @NotBlank String username,
            @JsonProperty("display_name") String displayName,
            String email) {}

    private static String str(Map<String, Object> body, String key) {
        if (body == null || !body.containsKey(key) || body.get(key) == null) {
            return null;
        }
        return String.valueOf(body.get(key));
    }
}
