package com.codearena.business.pay.web.external;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 支付域 HTTP 入口（桩）。 */
@RestController
@RequestMapping("/api/pay")
public class PayController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "domain", "pay",
                "deployable", "business-service",
                "stub", true);
    }

    @GetMapping("/orders")
    public Map<String, Object> listOrders() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("orders", java.util.List.of());
        body.put("stub", true);
        return body;
    }

    @PostMapping("/orders")
    public ResponseEntity<Map<String, Object>> createOrder(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "not_implemented");
        body.put("stub", true);
        body.put("message", "下单尚未实现；请勿接入真实支付渠道");
        return ResponseEntity.status(501).body(body);
    }
}
