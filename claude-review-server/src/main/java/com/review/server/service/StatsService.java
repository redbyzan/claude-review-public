package com.review.server.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// @MX:NOTE: LeaderboardService(로그 파일 기반 누적) + MeterRegistry(세션) 데이터를 통합하여 /stats 페이지에 제공
@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    private final LeaderboardService leaderboardService;
    private final PricingCatalog pricingCatalog;
    private final MeterRegistry registry;
    private final long startTime;

    public StatsService(LeaderboardService leaderboardService,
                        PricingCatalog pricingCatalog, MeterRegistry registry) {
        this.leaderboardService = leaderboardService;
        this.pricingCatalog = pricingCatalog;
        this.registry = registry;
        this.startTime = System.currentTimeMillis();
    }

    public StatsSnapshot getSnapshot() {
        var totalStats = leaderboardService.getTotalStats();
        var teams = leaderboardService.getLeaderboard();
        var global = leaderboardService.getGlobalAggregates();

        int totalReviews = totalStats.totalReviews();
        int totalTeams = totalStats.totalTeams();

        // 누적: 페르소나/모델/토큰 (로그 파일 기반)
        int mentorCount = 0;
        int cynicCount = 0;
        long totalTokens = 0;
        Map<String, Integer> modelCounts = new LinkedHashMap<>();

        for (var team : teams) {
            mentorCount += team.mentorCount();
            cynicCount += team.cynicCount();
            totalTokens += team.totalTokens();
            team.modelCounts().forEach((k, v) -> modelCounts.merge(k, v, Integer::sum));
        }

        long avgTokens = totalReviews > 0 ? totalTokens / totalReviews : 0;

        // @MX:NOTE: LLM API 비용 — 모델별 토큰 × 단가 합산 (미확인 모델은 기본 단가 적용)
        BigDecimal totalCost = BigDecimal.ZERO;
        for (var entry : global.modelInputTokens().entrySet()) {
            String model = entry.getKey();
            long inputTokens = entry.getValue();
            long outputTokens = global.modelOutputTokens().getOrDefault(model, 0L);
            totalCost = totalCost.add(pricingCatalog.calculateCost(model, inputTokens, outputTokens));
        }

        // 세션: MeterRegistry 기반 (재시작 시 초기화)
        long reviewSuccess = counterValue("review.requests.total", "status", "success");
        long reviewFail = counterValue("review.requests.total", "status", "fail");
        double successRate = (reviewSuccess + reviewFail) > 0
            ? (reviewSuccess * 100.0) / (reviewSuccess + reviewFail) : 100.0;

        long fallbackSuccess = counterValue("review.fallback.total", "status", "success");
        long fallbackFail = counterValue("review.fallback.total", "status", "fail");
        double fallbackRecoveryRate = (fallbackSuccess + fallbackFail) > 0
            ? (fallbackSuccess * 100.0) / (fallbackSuccess + fallbackFail) : 0.0;

        long rateLimitBlocked = counterValue("rate.limit.blocked.total");
        long githubApiErrors = counterValue("github.api.errors.total");

        // 누적: 기능별 사용량 (로그 파일 기반)
        int ctaInsertions = global.ctaInsertions();
        int rebuttalRequests = global.rebuttalCount();
        int conversationRequests = global.conversationCount();

        long uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000;

        return new StatsSnapshot(
            totalReviews, totalTeams,
            successRate, uptimeSeconds,
            totalTokens, avgTokens,
            mentorCount, cynicCount,
            modelCounts,
            fallbackSuccess, fallbackFail, fallbackRecoveryRate,
            rateLimitBlocked, githubApiErrors,
            ctaInsertions, rebuttalRequests, conversationRequests,
            totalCost,
            LocalDateTime.now(ZoneId.of("Asia/Seoul"))
        );
    }

    // @MX:NOTE: Micrometer find().tags().counter()는 첫 매치만 반환하므로, 동일 태그 조합이 여러 개면 합산 필요
    private long counterValue(String name, String... tags) {
        try {
            var counters = registry.find(name).tags(tags).counters();
            return counters.isEmpty() ? 0 : (long) counters.stream()
                .mapToDouble(Counter::count)
                .sum();
        } catch (Exception e) {
            return 0;
        }
    }

    public record StatsSnapshot(
        int totalReviews,
        int totalTeams,
        double successRate,
        long uptimeSeconds,
        long totalTokens,
        long avgTokensPerReview,
        int mentorCount,
        int cynicCount,
        Map<String, Integer> modelCounts,
        long fallbackSuccess,
        long fallbackFail,
        double fallbackRecoveryRate,
        long rateLimitBlocked,
        long githubApiErrors,
        int ctaInsertions,
        int rebuttalRequests,
        int conversationRequests,
        BigDecimal totalCost,
        LocalDateTime lastUpdated
    ) {
        public String formattedUptime() {
            long hours = uptimeSeconds / 3600;
            long minutes = (uptimeSeconds % 3600) / 60;
            if (hours >= 24) {
                long days = hours / 24;
                long remainHours = hours % 24;
                return days + "d " + remainHours + "h";
            }
            return hours + "h " + minutes + "m";
        }

        public String formattedTokens() {
            if (totalTokens >= 1_000_000) {
                return String.format("%.1fM", totalTokens / 1_000_000.0);
            }
            if (totalTokens >= 1_000) {
                return String.format("%.1fK", totalTokens / 1_000.0);
            }
            return String.valueOf(totalTokens);
        }

        public String formattedSuccessRate() {
            return String.format("%.1f%%", successRate);
        }

        public String formattedFallbackRate() {
            return String.format("%.1f%%", fallbackRecoveryRate);
        }

        public String formattedCost() {
            return "$" + totalCost.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
    }
}
