package com.review.server.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class SlackService {

    private static final Logger log = LoggerFactory.getLogger(SlackService.class);
    private static final String SLACK_API_URL = "https://slack.com/api/chat.postMessage";

    private final RestTemplate restTemplate;
    private final String botToken;
    private final String adminUserId;

    public SlackService(
            @Value("${slack.bot-token:}") String botToken,
            @Value("${slack.admin-user-id:}") String adminUserId) {
        this.restTemplate = new RestTemplate();
        this.botToken = botToken;
        this.adminUserId = adminUserId;
    }

    public boolean isEnabled() {
        return botToken != null && !botToken.isBlank()
                && adminUserId != null && !adminUserId.isBlank();
    }

    public boolean sendAlert(String title, String message) {
        if (!isEnabled()) {
            return false;
        }

        String text = String.format(":rotating_light: *%s*\n\n%s", title, message);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(botToken);

        Map<String, String> body = Map.of(
                "channel", adminUserId,
                "text", text);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    SLACK_API_URL,
                    new HttpEntity<>(body, headers),
                    Map.class);

            boolean ok = response != null && Boolean.TRUE.equals(response.get("ok"));
            if (!ok) {
                log.error("Slack alert API 오류: {}", response);
            }
            return ok;
        } catch (Exception e) {
            log.error("[Slack] Alert 전송 실패 — title:{}, cause:{}", title, e.getMessage(), e);
            return false;
        }
    }

    public boolean sendSecretRequest(String githubUsername, String githubRepo) {
        if (!isEnabled()) {
            log.warn("Slack 비활성화: bot-token 또는 admin-user-id 미설정");
            return false;
        }

        String text = String.format(
                ":key: *시크릿 키 요청*\n\n• GitHub: `%s`\n• Repo: `%s`\n\n새 사용자가 AI Code Review 시크릿 키를 요청했습니다.",
                githubUsername, githubRepo);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(botToken);

        Map<String, String> body = Map.of(
                "channel", adminUserId,
                "text", text);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    SLACK_API_URL,
                    new HttpEntity<>(body, headers),
                    Map.class);

            boolean ok = response != null && Boolean.TRUE.equals(response.get("ok"));
            if (!ok) {
                log.error("Slack API 오류: {}", response);
            }
            return ok;
        } catch (Exception e) {
            log.error("Slack DM 전송 실패", e);
            return false;
        }
    }
}
