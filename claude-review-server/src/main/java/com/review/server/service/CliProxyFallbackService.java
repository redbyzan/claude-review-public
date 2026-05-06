package com.review.server.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.review.server.dto.ReviewResponse;

/**
 * Claude API 실패 시 로컬 CLI 프록시 서버(Express)를 통해
 * gemini/codex CLI를 호출하는 폴백 서비스.
 * Express 서버는 호스트에서 실행되며, Spring Boot(Docker)에서 HTTP로 연동.
 */
@Service
public class CliProxyFallbackService {

    private static final Logger log = LoggerFactory.getLogger(CliProxyFallbackService.class);
    private static final long CIRCUIT_BREAKER_COOLDOWN_MS = 60_000;

    private final String proxyUrl;
    private final String provider;
    private final RestTemplate restTemplate;

    // 서킷 브레이커: 연속 실패 시 일정 시간 폴백 시도 생략
    private long lastFailTime = 0;
    private long consecutiveFails = 0;

    public CliProxyFallbackService(
            @Value("${cli-proxy.url:}") String proxyUrl,
            @Value("${cli-proxy.provider:gemini}") String provider) {
        this.proxyUrl = proxyUrl;
        this.provider = provider;
        this.restTemplate = createTimeoutRestTemplate();
        if (isEnabled()) {
            log.info("[CliProxy] 폴백 활성화 — url: {}, provider: {}", proxyUrl, provider);
        } else {
            log.info("[CliProxy] 폴백 비활성화 — proxy URL 미설정");
        }
    }

    private RestTemplate createTimeoutRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);   // 2초 연결 타임아웃
        factory.setReadTimeout(60_000);     // 60초 읽기 타임아웃 (AI 응답 대기)
        return new RestTemplate(factory);
    }

    public boolean isEnabled() {
        if (proxyUrl == null || proxyUrl.isBlank()) {
            return false;
        }
        return !isCircuitOpen();
    }

    private synchronized boolean isCircuitOpen() {
        return consecutiveFails >= 3
                && (System.currentTimeMillis() - lastFailTime) < CIRCUIT_BREAKER_COOLDOWN_MS;
    }

    public String getProvider() {
        return provider;
    }

    public ReviewResponse review(String systemPrompt, String userPrompt) {
        long start = System.currentTimeMillis();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "systemPrompt", systemPrompt,
            "userPrompt", userPrompt,
            "provider", provider
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ResponseEntity<Map> resp = restTemplate.postForEntity(
            proxyUrl + "/review", request, Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> r = resp.getBody();
        if (r == null) {
            throw new RuntimeException("CLI 프록시 응답이 비어있음");
        }

        String review = (String) r.get("review");
        String model = (String) r.get("model");
        long elapsed = ((Number) r.get("elapsedMs")).longValue();

        long totalElapsed = System.currentTimeMillis() - start;
        log.info("[CliProxy] 폴백 성공 — {}ms (CLI: {}ms) | model: {}",
            totalElapsed, elapsed, model);

        resetCircuit();
        return new ReviewResponse(review, null, model, totalElapsed);
    }

    // 서킷 브레이커: 실패 기록 (ReviewService catch에서 호출)
    public synchronized void recordFailure() {
        lastFailTime = System.currentTimeMillis();
        consecutiveFails++;
        if (consecutiveFails == 3) {
            log.warn("[CliProxy] 서킷 브레이커 활성화 — {}회 연속 실패, {}ms간 폴백 생략",
                consecutiveFails, CIRCUIT_BREAKER_COOLDOWN_MS);
        }
    }

    private synchronized void resetCircuit() {
        consecutiveFails = 0;
    }
}
