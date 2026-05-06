package com.review.server.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class LeaderboardService {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final LocalDateTime TZ_CUTOFF = LocalDateTime.of(2026, 4, 15, 19, 0);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Value("${review.log-dir:/app/review-logs}")
    private String logDir;

    private volatile List<TeamStats> leaderboardCache = List.of();
    private volatile TotalStats totalStatsCache = new TotalStats(0, 0, 0);
    private volatile GlobalAggregates globalCache = new GlobalAggregates(0, 0, 0, 0, Map.of(), Map.of(), Map.of());

    public record TeamStats(
        String repo,
        String teamName,
        int reviewCount,
        long totalDiffSize,
        long avgDiffSize,
        long totalTokens,
        int mentorCount,
        int cynicCount,
        String lastReviewTime,
        Map<String, Integer> modelCounts,
        String lastPrTitle,
        String lastPrAuthor,
        int lastPrNumber
    ) {
        public String topModel() {
            return modelCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");
        }

        public String githubPrUrl() {
            if (lastPrNumber > 0 && repo != null && !"unknown".equals(repo)) {
                return "https://github.com/" + repo + "/pull/" + lastPrNumber;
            }
            return null;
        }
    }

    public record TotalStats(int totalReviews, int totalTeams, long totalDiffBytes) {}

    // @MX:NOTE: 로그 파일 기반 전체 누적 통계 (팀 경계 없이 집계)
    public record GlobalAggregates(
        int reviewCount, int conversationCount, int rebuttalCount,
        int ctaInsertions,
        Map<String, Long> modelInputTokens,
        Map<String, Long> modelOutputTokens,
        Map<String, Integer> modelReviewCounts
    ) {}

    @PostConstruct
    public void init() {
        refresh();
    }

    @Scheduled(fixedDelay = 300_000)
    public void refresh() {
        try {
            Path dir = Path.of(logDir);
            if (!Files.exists(dir)) {
                log.info("[Leaderboard] 로그 디렉토리 없음: {}", logDir);
                leaderboardCache = List.of();
                totalStatsCache = new TotalStats(0, 0, 0);
                return;
            }

            Map<String, TeamBuilder> map = new ConcurrentHashMap<>();
            int[] totals = {0};
            GlobalBuilder global = new GlobalBuilder();

            Files.walk(dir)
                .filter(p -> p.toString().endsWith(".md"))
                .forEach(p -> parseFile(p, map, totals, global));

            List<TeamStats> sorted = map.values().stream()
                .map(TeamBuilder::build)
                .sorted(Comparator.comparingInt(TeamStats::reviewCount).reversed())
                .toList();

            long totalDiff = sorted.stream()
                .mapToLong(TeamStats::totalDiffSize).sum();

            leaderboardCache = sorted;
            totalStatsCache = new TotalStats(totals[0], sorted.size(), totalDiff);
            globalCache = global.build();

            log.info("[Leaderboard] 갱신 완료 — {}개 팀, {}건 리뷰", sorted.size(), totals[0]);
        } catch (IOException e) {
            log.warn("[Leaderboard] 갱신 실패: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void parseFile(Path file, Map<String, TeamBuilder> map, int[] totals, GlobalBuilder global) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int start = content.indexOf("---\n");
            if (start < 0) return;
            int end = content.indexOf("\n---\n", start + 4);
            if (end < 0) return;

            String yaml = content.substring(start + 4, end);
            Yaml parser = new Yaml();
            Map<String, Object> data = parser.load(yaml);

            String repo = String.valueOf(data.getOrDefault("repo", "unknown"));
            if ("unknown".equals(repo) || "test/repo".equals(repo)) return;

            totals[0]++;

            // @MX:NOTE: type 필드로 review/conversation/rebuttal 구분 (누락 시 "review")
            String type = String.valueOf(data.getOrDefault("type", "review"));
            if ("null".equals(type) || type.isEmpty()) type = "review";
            switch (type) {
                case "conversation" -> global.conversationCount++;
                case "rebuttal" -> global.rebuttalCount++;
                default -> global.reviewCount++;
            }

            // CTA 삽입 여부
            Object ctaObj = data.get("ctaIncluded");
            if (ctaObj instanceof Boolean cta && cta) global.ctaInsertions++;

            TeamBuilder b = map.computeIfAbsent(repo, TeamBuilder::new);
            b.reviewCount++;

            Object diffObj = data.get("diffSize");
            if (diffObj instanceof Number n) b.totalDiffSize += n.longValue();

            String model = String.valueOf(data.getOrDefault("model", ""));
            boolean hasModel = !model.isEmpty() && !"null".equals(model);
            if (hasModel) global.modelReviewCounts.merge(model, 1, Integer::sum);

            Object inTok = data.get("inputTokens");
            Object outTok = data.get("outputTokens");
            if (inTok instanceof Number in && outTok instanceof Number out) {
                long inVal = in.longValue();
                long outVal = out.longValue();
                b.totalTokens += inVal + outVal;
                // 모델별 토큰 분리 집계
                if (hasModel) {
                    global.modelInputTokens.merge(model, inVal, Long::sum);
                    global.modelOutputTokens.merge(model, outVal, Long::sum);
                }
            }

            String persona = String.valueOf(data.getOrDefault("persona", ""));
            if ("mentor".equals(persona)) b.mentorCount++;
            else if ("cynic".equals(persona)) b.cynicCount++;

            if (hasModel) {
                b.modelCounts.merge(model, 1, Integer::sum);
            }

            String ts = String.valueOf(data.getOrDefault("timestamp", ""));
            if (!ts.isEmpty() && (b.lastTimestamp == null || ts.compareTo(b.lastTimestamp) > 0)) {
                b.lastTimestamp = ts;
                b.lastPrTitle = String.valueOf(data.getOrDefault("prTitle", ""));
                b.lastPrAuthor = String.valueOf(data.getOrDefault("prAuthor", ""));
                Object prNumObj = data.get("prNumber");
                b.lastPrNumber = prNumObj instanceof Number n ? n.intValue() : 0;
            }
        } catch (Exception e) {
            log.debug("[Leaderboard] 파일 파싱 스킵: {} — {}", file.getFileName(), e.getMessage());
        }
    }

    public List<TeamStats> getLeaderboard() {
        return leaderboardCache;
    }

    public List<TeamStats> getTopTeams(int n) {
        return leaderboardCache.stream().limit(n).toList();
    }

    public TotalStats getTotalStats() {
        return totalStatsCache;
    }

    public GlobalAggregates getGlobalAggregates() {
        return globalCache;
    }

    @Value("${review.server-url:https://review.example.com}")
    private String serverUrl;

    public String getLeaderboardNotice(String repo) {
        if (leaderboardCache.isEmpty()) return null;

        int rank = 0;
        int reviewCount = 0;
        for (int i = 0; i < leaderboardCache.size(); i++) {
            if (leaderboardCache.get(i).repo().equals(repo)) {
                rank = i + 1;
                reviewCount = leaderboardCache.get(i).reviewCount();
                break;
            }
        }
        if (rank == 0) return null;

        return String.format("**<a href=\"%s/leaderboard\" target=\"_blank\">리더보드</a>** — 현재 %d위 (리뷰 %d건) | 전체 %d개 팀 참여 중",
            serverUrl, rank, reviewCount, totalStatsCache.totalTeams());
    }

    private static class GlobalBuilder {
        int reviewCount;
        int conversationCount;
        int rebuttalCount;
        int ctaInsertions;
        final Map<String, Long> modelInputTokens = new ConcurrentHashMap<>();
        final Map<String, Long> modelOutputTokens = new ConcurrentHashMap<>();
        final Map<String, Integer> modelReviewCounts = new ConcurrentHashMap<>();

        GlobalAggregates build() {
            return new GlobalAggregates(reviewCount, conversationCount, rebuttalCount,
                ctaInsertions, Map.copyOf(modelInputTokens), Map.copyOf(modelOutputTokens),
                Map.copyOf(modelReviewCounts));
        }
    }

    private static class TeamBuilder {
        final String repo;
        final String teamName;
        int reviewCount;
        long totalDiffSize;
        long totalTokens;
        int mentorCount;
        int cynicCount;
        String lastTimestamp;
        final Map<String, Integer> modelCounts = new ConcurrentHashMap<>();
        String lastPrTitle;
        String lastPrAuthor;
        int lastPrNumber;

        TeamBuilder(String repo) {
            this.repo = repo;
            int slash = repo.lastIndexOf('/');
            this.teamName = slash >= 0 ? repo.substring(slash + 1) : repo;
        }

        TeamStats build() {
            long avg = reviewCount > 0 ? totalDiffSize / reviewCount : 0;
            String lastTime = "";
            if (lastTimestamp != null && lastTimestamp.length() >= 16) {
                try {
                    LocalDateTime ldt = LocalDateTime.parse(lastTimestamp.substring(0,
                        Math.min(lastTimestamp.length(), 26)));
                    if (ldt.isBefore(TZ_CUTOFF)) {
                        ldt = ZonedDateTime.of(ldt, UTC).withZoneSameInstant(SEOUL).toLocalDateTime();
                    }
                    lastTime = ldt.format(TIME_FMT);
                } catch (Exception ignored) {
                    lastTime = lastTimestamp.substring(0, 16);
                }
            }
            return new TeamStats(repo, teamName, reviewCount, totalDiffSize, avg,
                totalTokens, mentorCount, cynicCount, lastTime,
                Map.copyOf(modelCounts), lastPrTitle, lastPrAuthor, lastPrNumber);
        }
    }
}
