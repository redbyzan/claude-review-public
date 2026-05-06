package com.review.server.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeployController {

    private static final Logger log = LoggerFactory.getLogger(DeployController.class);

    @Value("${deploy.webhook-secret:}")
    private String webhookSecret;

    @Value("${review.log-dir:/app/review-logs}")
    private String logDir;

    @PostMapping("/api/deploy")
    public ResponseEntity<Map<String, Object>> deploy(
            @RequestHeader(value = "X-Deploy-Secret", required = false) String secret,
            @RequestBody(required = false) Map<String, Object> body) {

        if (webhookSecret.isBlank()) {
            log.warn("[Deploy] webhook-secret 미설정");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("ok", false, "error", "배포 webhook이 설정되지 않았습니다."));
        }

        if (!webhookSecret.equals(secret)) {
            log.warn("[Deploy] 인증 실패");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "error", "인증 실패"));
        }

        try {
            Path triggerFile = Path.of(logDir).resolve("deploy-trigger");
            String content = "triggered=" + LocalDateTime.now() + "\n";
            Files.writeString(triggerFile, content);
            log.info("[Deploy] 트리거 파일 생성: {}", triggerFile);
            return ResponseEntity.ok(Map.of("ok", true, "message", "배포가 트리거되었습니다."));
        } catch (IOException e) {
            log.error("[Deploy] 트리거 파일 생성 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("ok", false, "error", "배포 트거 실패"));
        }
    }
}
