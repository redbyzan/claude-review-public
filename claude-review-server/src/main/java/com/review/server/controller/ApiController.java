package com.review.server.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.review.server.service.LeaderboardService;
import com.review.server.service.StatsService;

/**
 * 대시보드 자동화를 위한 JSON API 컨트롤러.
 * StatsSnapshot과 TeamStats에서 민감 필드를 제외한 큐레이션된 응답을 제공한다.
 */
@RestController
@RequestMapping("/api/dashboard")
public class ApiController {

    private final StatsService statsService;
    private final LeaderboardService leaderboardService;

    public ApiController(StatsService statsService, LeaderboardService leaderboardService) {
        this.statsService = statsService;
        this.leaderboardService = leaderboardService;
    }

    // @MX:NOTE: 대시보드용 요약 통계. totalCost, lastPrAuthor 등 민감 정보는 제외
    public record DashboardSummary(
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
        int ctaInsertions,
        int rebuttalRequests,
        int conversationRequests,
        String lastUpdated
    ) {}

    // @MX:NOTE: 대시보드용 팀 순위. lastPrAuthor, totalCost 제외
    public record DashboardTeam(
        String repo,
        String teamName,
        int reviewCount,
        long totalTokens,
        int mentorCount,
        int cynicCount,
        String lastReviewTime,
        String lastPrTitle,
        int lastPrNumber
    ) {}

    @GetMapping("/summary")
    public ResponseEntity<DashboardSummary> summary() {
        var snapshot = statsService.getSnapshot();
        var dto = new DashboardSummary(
            snapshot.totalReviews(),
            snapshot.totalTeams(),
            snapshot.successRate(),
            snapshot.uptimeSeconds(),
            snapshot.totalTokens(),
            snapshot.avgTokensPerReview(),
            snapshot.mentorCount(),
            snapshot.cynicCount(),
            snapshot.modelCounts(),
            snapshot.fallbackSuccess(),
            snapshot.fallbackFail(),
            snapshot.fallbackRecoveryRate(),
            snapshot.ctaInsertions(),
            snapshot.rebuttalRequests(),
            snapshot.conversationRequests(),
            snapshot.lastUpdated() != null
                ? snapshot.lastUpdated().toString()
                : null
        );
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/leaderboard")
    public ResponseEntity<List<DashboardTeam>> leaderboard(
        @RequestParam(defaultValue = "10") int limit
    ) {
        int safeLimit = Math.max(1, limit);
        var teams = leaderboardService.getLeaderboard().stream()
            .limit(safeLimit)
            .map(t -> new DashboardTeam(
                t.repo(),
                t.teamName(),
                t.reviewCount(),
                t.totalTokens(),
                t.mentorCount(),
                t.cynicCount(),
                t.lastReviewTime(),
                t.lastPrTitle(),
                t.lastPrNumber()
            ))
            .toList();
        return ResponseEntity.ok(teams);
    }
}
