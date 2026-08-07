package com.codearena.business.team.web.external;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组队域 HTTP 入口（桩）。实现房间/邀请时在本包扩展 service / repository，勿散落到其它域。
 */
@RestController
@RequestMapping("/api/team")
public class TeamController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "domain", "team",
                "deployable", "business-service",
                "stub", true);
    }

    @GetMapping("/rooms")
    public Map<String, Object> listRooms() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("rooms", List.of());
        body.put("stub", true);
        body.put("message", "组队房间列表待实现；表结构见 V2__domain_stubs.sql");
        return body;
    }

    @PostMapping("/rooms")
    public ResponseEntity<Map<String, Object>> createRoom(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "not_implemented");
        body.put("stub", true);
        body.put("message", "创建组队房间尚未实现");
        if (req != null && !req.isEmpty()) {
            body.put("echo", req);
        }
        return ResponseEntity.status(501).body(body);
    }

    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<Map<String, Object>> getRoom(@PathVariable String roomId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "not_found");
        body.put("room_id", roomId);
        body.put("stub", true);
        return ResponseEntity.status(404).body(body);
    }
}
