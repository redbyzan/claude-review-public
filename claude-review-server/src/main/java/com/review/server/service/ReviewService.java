package com.review.server.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.review.server.dto.ReviewRequest;
import com.review.server.dto.ReviewResponse;
import com.review.server.prompt.PromptProvider;
import com.review.server.prompt.PromptProvider.PromptContext;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ChatClient chatClient;
    private final PromptProvider promptProvider;
    private final ReviewLogService reviewLogService;
    private final CliProxyFallbackService cliProxyFallback;
    private final ChangelogService changelogService;
    private final LeaderboardService leaderboardService;
    private final RebuttalService rebuttalService;
    private final MonitorService monitorService;
    private final MeterRegistry registry;

    private final Counter inputTokenCounter;
    private final Counter outputTokenCounter;
    private final Timer reviewTimer;

    @Value("${review.cta.enabled:true}")
    private boolean ctaEnabled;

    public ReviewService(ChatClient.Builder chatClientBuilder, PromptProvider promptProvider,
                         ReviewLogService reviewLogService, CliProxyFallbackService cliProxyFallback,
                         ChangelogService changelogService, LeaderboardService leaderboardService,
                         RebuttalService rebuttalService, MonitorService monitorService,
                         MeterRegistry registry) {
        this.chatClient = chatClientBuilder.build();
        this.promptProvider = promptProvider;
        this.reviewLogService = reviewLogService;
        this.cliProxyFallback = cliProxyFallback;
        this.changelogService = changelogService;
        this.leaderboardService = leaderboardService;
        this.rebuttalService = rebuttalService;
        this.monitorService = monitorService;
        this.registry = registry;

        this.inputTokenCounter = Counter.builder("review.tokens").tag("type", "input").register(registry);
        this.outputTokenCounter = Counter.builder("review.tokens").tag("type", "output").register(registry);
        this.reviewTimer = Timer.builder("review.duration")
            .description("AI review processing time").register(registry);
    }

    @PostConstruct
    void logCtaStatus() {
        log.info("[CTA] {}", ctaEnabled ? "활성화" : "비활성화");
    }

    public ReviewResponse review(ReviewRequest request) {
        String label = "%s#%d".formatted(request.repo(), request.prNumber());
        log.info("[Review] START — {} | author:{} | \"{}\"", label, request.prAuthor(), request.prTitle());

        PromptContext ctx = promptProvider.buildPromptContext(request);
        String systemPrompt = ctx.systemPrompt();
        String userPrompt = ctx.userPrompt();

        long start = System.currentTimeMillis();

        try {
            ChatResponse chatResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .chatResponse();

            String content = chatResponse.getResult().getOutput().getText();
            long elapsed = System.currentTimeMillis() - start;

            // usage 추출
            ReviewResponse.Usage usage = null;
            String model = null;
            var metadata = chatResponse.getMetadata();
            if (metadata != null) {
                var tokenUsage = metadata.getUsage();
                if (tokenUsage != null) {
                    usage = new ReviewResponse.Usage(
                        (long) tokenUsage.getPromptTokens(),
                        (long) tokenUsage.getCompletionTokens()
                    );
                }
                model = metadata.getModel();
            }

            log.info("[Review] DONE — {} | {}ms | persona:{}", label, elapsed, ctx.personaName());
            content = prependNotice(content, request.repo());
            boolean ctaIncluded = ctaEnabled;
            content = injectCTA(content);
            ReviewResponse response = new ReviewResponse(content, usage, model, elapsed);
            reviewLogService.logReview(request, response, ctx, ctaIncluded);

            registry.counter("review.requests.total", "status", "success",
                "persona", ctx.personaName(), "model", model != null ? model : "unknown").increment();
            reviewTimer.record(elapsed, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (usage != null) {
                if (usage.inputTokens() != null) inputTokenCounter.increment(usage.inputTokens());
                if (usage.outputTokens() != null) outputTokenCounter.increment(usage.outputTokens());
            }

            monitorService.recordReviewComplete(request.repo(), ctx.personaName(),
                model != null ? model : "unknown", ctaEnabled, false,
                elapsed, usage != null && usage.inputTokens() != null ? usage.inputTokens() : 0,
                usage != null && usage.outputTokens() != null ? usage.outputTokens() : 0);

            return response;

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[Review] Claude 실패 — {} | {}ms | {}", label, elapsed, e.getMessage());

            if (cliProxyFallback.isEnabled()) {
                log.info("[Review] CLI 프록시 폴백 시도 — {}", label);
                try {
                    ReviewResponse fallbackResponse = cliProxyFallback.review(systemPrompt, userPrompt);
                    // 폴백 응답에도 notice + CTA 삽입
                    String reviewWithNotice = prependNotice(fallbackResponse.review(), request.repo());
                    reviewWithNotice = injectCTA(reviewWithNotice);
                    fallbackResponse = new ReviewResponse(
                        reviewWithNotice, fallbackResponse.usage(),
                        fallbackResponse.model(), fallbackResponse.elapsedMs());
                    reviewLogService.logReview(request, fallbackResponse, ctx, ctaEnabled);
                    registry.counter("review.requests.total", "status", "success",
                        "persona", ctx.personaName(), "model", "cli-fallback").increment();
                    registry.counter("review.fallback.total",
                        "provider", cliProxyFallback.getProvider(), "status", "success").increment();
                    reviewTimer.record(elapsed + fallbackResponse.elapsedMs(),
                        java.util.concurrent.TimeUnit.MILLISECONDS);
                    log.info("[Review] CLI 프록시 폴백 성공 — {}", label);
                    return fallbackResponse;
                } catch (Exception fallbackEx) {
                    cliProxyFallback.recordFailure();
                    registry.counter("review.fallback.total",
                        "provider", cliProxyFallback.getProvider(), "status", "fail").increment();
                    log.error("[Review] CLI 프록시 폴백도 실패 — {} | {}", label, fallbackEx.getMessage());
                }
            }

            registry.counter("review.requests.total", "status", "fail",
                "persona", ctx.personaName(), "model", "unknown").increment();
            monitorService.recordReviewError(request.repo(), "claude_api_fail", e.getMessage());
            throw new ReviewException("AI 리뷰 생성 실패: " + e.getMessage(), e);
        }
    }

    public static class ReviewException extends RuntimeException {
        public ReviewException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private String prependNotice(String content, String repo) {
        String changelogNotice = changelogService.getNoticeMarkdown();
        String lbNotice = leaderboardService.getLeaderboardNotice(repo);

        if (changelogNotice == null && lbNotice == null) return content;

        StringBuilder sb = new StringBuilder();
        if (lbNotice != null) {
            sb.append("> ").append(lbNotice).append("\n");
        }
        if (changelogNotice != null) {
            sb.append("> ").append(changelogNotice).append("\n");
        }
        return sb.append("\n").append(content).toString();
    }

    // @MX:NOTE: 리뷰 하단에 @sparta 멘션 유도 텍스트를 삽입합니다.
    // 프롬프트에 이미 "생각해보기 + @sparta 유도" 섹션이 포함되어 있으나,
    // AI가 섹션을 누락할 경우를 대비해 하단에 보조 유도를 제공합니다.
    private String injectCTA(String content) {
        if (!ctaEnabled) return content;

        // @MX:NOTE: 프롬프트에서 @sparta 유도를 이미 생성했으면 하단 CTA 생략 (중복 방지)
        if (content.contains("@sparta")) {
            registry.counter("review.cta.insertion", "result", "skip_prompt").increment();
            return content;
        }

        String bottomCta = "\n---\n"
            + "> 💬 **리뷰에 대해 궁금한 점이 있나요?**"
            + " 코멘트에 `@sparta` 를 남겨보세요!\n"
            + "> 예: `@sparta 이 코드에서 동시성 이슈가 발생할 수 있나요?`\n";

        registry.counter("review.cta.insertion", "result", "success").increment();

        return content + bottomCta;
    }
}
