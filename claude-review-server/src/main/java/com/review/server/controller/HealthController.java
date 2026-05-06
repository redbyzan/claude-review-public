package com.review.server.controller;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private final long startTime = System.currentTimeMillis();

    @Value("${review.server.role:active}")
    private String role;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "ok",
            "timestamp", LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(FMT),
            "uptime", (System.currentTimeMillis() - startTime) / 1000,
            "role", role
        );
    }
}
