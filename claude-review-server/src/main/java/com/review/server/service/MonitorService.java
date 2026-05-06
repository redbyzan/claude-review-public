package com.review.server.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;

@Service
public class MonitorService {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    private final MeterRegistry registry;
    private final SlackService slackService;
    private final boolean slackEnabled;

    // 이상징후 카운터 (스케줄러가 주기적으로 확인 후 초기화)
    private final AtomicLong authBlockedCount = new AtomicLong();
    private final AtomicLong reviewFailCount = new AtomicLong();
    private final AtomicLong rateLimitHitCount = new AtomicLong();

    // 알림 cooldown (에러 유형별 마지막 알림 시간)
    private final Map<String, Long> lastAlertTimes = new ConcurrentHashMap<>();

    @Value("${monitor.alert.cooldown-ms:300000}")
    private long alertCooldownMs;

    @Value("${monitor.alert.auth-blocked-threshold:10}")
    private long authBlockedThreshold;

    @Value("${monitor.alert.review-fail-threshold:5}")
    private long reviewFailThreshold;

    @Value("${monitor.alert.rate-limit-threshold:20}")
    private long rateLimitThreshold;

    // 외부 의존성 상태 추적 (HealthIndicator에서 참조)
    private volatile Instant lastReviewSuccess = Instant.now();
    private volatile Instant lastReviewFail;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    public MonitorService(MeterRegistry registry, SlackService slackService) {
        this.registry = registry;
        this.slackService = slackService;
        this.slackEnabled = slackService.isEnabled();
    }

    // ── 기능 건전성 ──────────────────────────────

    public void recordFeatureHealth(String feature, String result) {
        registry.counter("feature.health", "feature", feature, "result", result).increment();
    }

    // ── 리뷰 완료 메타데이터 ──────────────────────

    public void recordReviewComplete(String repo, String persona, String model,
                                     boolean ctaIncluded, boolean hasRequirements,
                                     long elapsedMs, long inputTokens, long outputTokens) {
        registry.counter("review.metadata", "status", "success",
                "team", teamTag(repo), "persona", persona, "model", model,
                "cta_included", String.valueOf(ctaIncluded),
                "has_requirements", String.valueOf(hasRequirements)).increment();

        registry.counter("review.tokens", "type", "input", "model", model).increment(inputTokens);
        registry.counter("review.tokens", "type", "output", "model", model).increment(outputTokens);

        lastReviewSuccess = Instant.now();
        consecutiveFailures.set(0);
    }

    // ── 리뷰 실패 ────────────────────────────────

    public void recordReviewError(String repo, String errorCode, String message) {
        registry.counter("review.metadata", "status", "fail",
                "team", teamTag(repo), "error", errorCode).increment();
        reviewFailCount.incrementAndGet();

        lastReviewFail = Instant.now();
        consecutiveFailures.incrementAndGet();
    }

    // ── 인증 차단 ────────────────────────────────

    public void recordAuthBlocked(String repo, List<String> reasons) {
        String normalizedReason = normalizeReason(reasons);
        registry.counter("auth.blocked.total", "reason", normalizedReason).increment();
        authBlockedCount.incrementAndGet();
    }

    // ── Rate Limit ───────────────────────────────

    public void recordRateLimitHit(String path) {
        registry.counter("rate.limit.blocked.total").increment();
        rateLimitHitCount.incrementAndGet();
    }

    // ── 반박봇 결과 ──────────────────────────────

    public void recordRebuttalResult(boolean hasRebuttal) {
        registry.counter("rebuttal.results.total", "has_rebuttal", String.valueOf(hasRebuttal)).increment();
    }

    // ── 대화 결과 ────────────────────────────────

    public void recordConversationResult(boolean success) {
        registry.counter("conversation.results.total", "status", success ? "success" : "fail").increment();
    }

    // ── 리더보드 갱신 ─────────────────────────────

    public void recordLeaderboardRefresh(boolean success) {
        registry.counter("leaderboard.refresh", "status", success ? "success" : "fail").increment();
    }

    // ── HealthIndicator 지원 ──────────────────────

    public Instant getLastReviewSuccess() {
        return lastReviewSuccess;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    // ── 정기 이상징후 감지 (@Scheduled) ───────────

    @Scheduled(fixedRateString = "${monitor.alert.check-interval-ms:60000}")
    public void checkAnomalies() {
        checkAndAlert("auth_blocked", authBlockedCount, authBlockedThreshold);
        checkAndAlert("review_fail", reviewFailCount, reviewFailThreshold);
        checkAndAlert("rate_limit", rateLimitHitCount, rateLimitThreshold);
    }

    private void checkAndAlert(String type, AtomicLong counter, long threshold) {
        long count = counter.get();
        if (count < threshold) {
            return;
        }

        counter.set(0);

        if (!slackEnabled || isInCooldown(type)) {
            return;
        }

        String message = String.format("[%s] 최근 1분간 %d건 감지 (임계치: %d)", type, count, threshold);
        boolean sent = slackService.sendAlert("[AI Review Alert] " + type, message);
        if (sent) {
            lastAlertTimes.put(type, System.currentTimeMillis());
            log.info("[Monitor] Slack 알림 전송: {} — {}건", type, count);
        }
    }

    private boolean isInCooldown(String type) {
        Long lastTime = lastAlertTimes.get(type);
        return lastTime != null && (System.currentTimeMillis() - lastTime) < alertCooldownMs;
    }

    private String teamTag(String repo) {
        if (repo == null || !repo.contains("/")) {
            return "unknown";
        }
        return repo.split("/")[1].replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    // 인증 차단 사유를 고정된 코드값으로 정규화 (태그 카디널리티 제한)
    private static final Set<String> KNOWN_REASONS = Set.of(
            "invalid_secret", "invalid_repo", "missing_header"
    );

    private String normalizeReason(List<String> reasons) {
        for (String reason : reasons) {
            if (KNOWN_REASONS.contains(reason)) {
                return reason;
            }
        }
        return "other";
    }
}
