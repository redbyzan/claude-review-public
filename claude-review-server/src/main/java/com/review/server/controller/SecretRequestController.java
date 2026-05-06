package com.review.server.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.review.server.service.SlackService;

@RestController
public class SecretRequestController {

    private final SlackService slackService;

    public SecretRequestController(SlackService slackService) {
        this.slackService = slackService;
    }

    public record SecretRequest(String githubUsername, String githubRepo) {}

    @PostMapping("/api/request-secret")
    public ResponseEntity<Map<String, Object>> requestSecret(@RequestBody SecretRequest request) {
        if (!slackService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("ok", false, "error", "현재 시크릿 키 요청 기능이 비활성화되어 있습니다."));
        }

        if (request.githubUsername() == null || request.githubUsername().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "error", "GitHub username을 입력해주세요."));
        }

        boolean sent = slackService.sendSecretRequest(
                request.githubUsername().trim(),
                request.githubRepo() != null ? request.githubRepo().trim() : "");

        if (sent) {
            return ResponseEntity.ok(Map.of("ok", true,
                    "message", "요청이 전송되었습니다. 관리자 슬랙을 확인 후 안내드립니다."));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("ok", false, "error", "요청 전송에 실패했습니다. 잠시 후 다시 시도해주세요."));
    }
}
