package com.codearena.gateway.fallback;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class FallbackController {

    @RequestMapping("/fallback/business")
    public Mono<ResponseEntity<Map<String, Object>>> businessFallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "status", "error",
                        "message", "business-service temporarily unavailable",
                        "fallback", true)));
    }

    @RequestMapping("/fallback/llm")
    public Mono<ResponseEntity<Map<String, Object>>> llmFallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "status", "error",
                        "message", "llm-service temporarily unavailable",
                        "fallback", true)));
    }
}
