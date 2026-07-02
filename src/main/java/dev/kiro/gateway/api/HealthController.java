package dev.kiro.gateway.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
class HealthController {

    private final long startTime = System.currentTimeMillis();

    @GetMapping("/health")
    Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "version", "0.2.0",
                "uptime", (System.currentTimeMillis() - startTime) / 1000,
                "timestamp", System.currentTimeMillis()
        );
    }
}
