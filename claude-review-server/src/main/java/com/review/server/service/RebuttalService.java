package com.review.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.review.server.dto.RebuttalRequest;
import com.review.server.dto.RebuttalResponse;
import com.review.server.prompt.PromptProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class RebuttalService {

    private static final Logger log = LoggerFactory.getLogger(RebuttalService.class);
    private static final String NO_REBUTTAL = "NO_REBUTTAL";

    private final ChatClient chatClient;
    private final PromptProvider promptProvider;
    private final CliProxyFallbackService cliProxyFallback;
    private final MeterRegistry registry;
    private final boolean enabled;
    private final AnthropicChatOptions rebuttalOptions;

    private final Counter rebuttalCounter;
    private final Timer rebuttalTimer;

    private static final DateTimeFormatter DIR_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");

    @Value("${review.log-dir:/app/review-logs}")
    private String logDir;

    public RebuttalService(ChatClient.Builder chatClientBuilder,
                           PromptProvider promptProvider,
                           CliProxyFallbackService cliProxyFallback,
                           MeterRegistry registry,
                           @Value("${review.rebuttal.enabled:false}") boolean enabled,
                           @Value("${review.rebuttal.model:claude-haiku-4-5-20251001}") String model) {
        this.chatClient = chatClientBuilder.build();
        this.promptProvider = promptProvider;
        this.cliProxyFallback = cliProxyFallback;
        this.registry = registry;
        this.enabled = enabled;
        this.rebuttalOptions = AnthropicChatOptions.builder().model(model).build();

        this.rebuttalCounter = Counter.builder("rebuttal.requests.total")
            .description("Rebuttal requests total").register(registry);
        this.rebuttalTimer = Timer.builder("rebuttal.duration")
            .description("Rebuttal generation time").register(registry);

        log.info("[Rebuttal] 초기화 완료 | enabled: {} | model: {}", enabled, model);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public RebuttalResponse generateRebuttal(RebuttalRequest request) {
        if (!enabled) {
            log.debug("[Rebuttal] 비활성화 상태 — 스킵");
            return new RebuttalResponse(null, null, null, 0);
        }

        String label = "%s#%d".formatted(request.repo(), request.prNumber());
        log.info("[Rebuttal] START — {}", label);

        String systemPrompt = promptProvider.getRebuttalPrompt();
        if (systemPrompt == null || systemPrompt.isBlank()) {
            log.warn("[Rebuttal] 프롬프트 미로드 — 스킵");
            return new RebuttalResponse(null, null, null, 0);
        }

        String userPrompt = promptProvider.buildRebuttalUserPrompt(
            request.reviewText(), request.diff());

        long start = System.currentTimeMillis();

        try {
            ChatResponse chatResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .options(rebuttalOptions)
                .call()
                .chatResponse();

            String content = chatResponse.getResult().getOutput().getText();
            long elapsed = System.currentTimeMillis() - start;

            RebuttalResponse.Usage usage = null;
            String model = null;
            var metadata = chatResponse.getMetadata();
            if (metadata != null) {
                var tokenUsage = metadata.getUsage();
                if (tokenUsage != null) {
                    usage = new RebuttalResponse.Usage(
                        (long) tokenUsage.getPromptTokens(),
                        (long) tokenUsage.getCompletionTokens()
                    );
                }
                model = metadata.getModel();
            }

            // NO_REBUTTAL 체크 (대소문자 무시, trim)
            String rebuttal = null;
            if (content != null && !content.trim().equalsIgnoreCase(NO_REBUTTAL)
                && !content.isBlank()) {
                rebuttal = content.trim();
            }

            log.info("[Rebuttal] DONE — {} | {}ms | hasRebuttal: {}",
                label, elapsed, rebuttal != null);

            registry.counter("rebuttal.requests.total", "status", "success",
                "has_rebuttal", String.valueOf(rebuttal != null)).increment();
            rebuttalTimer.record(elapsed, java.util.concurrent.TimeUnit.MILLISECONDS);

            logRebuttal(request, rebuttal, usage, model, elapsed);

            return new RebuttalResponse(rebuttal, usage, model, elapsed);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[Rebuttal] Claude 실패 — {} | {}ms | {}", label, elapsed, e.getMessage());

            // 폴백 시도
            if (cliProxyFallback.isEnabled()) {
                try {
                    var fallbackResult = cliProxyFallback.review(systemPrompt, userPrompt);
                    String fallbackContent = fallbackResult.review();
                    String fallbackRebuttal = null;
                    if (fallbackContent != null && !fallbackContent.trim().equalsIgnoreCase(NO_REBUTTAL)
                        && !fallbackContent.isBlank()) {
                        fallbackRebuttal = fallbackContent.trim();
                    }
                    long totalElapsed = System.currentTimeMillis() - start;
                    log.info("[Rebuttal] CLI 폴백 성공 — {} | hasRebuttal: {}", label, fallbackRebuttal != null);
                    registry.counter("rebuttal.fallback.total",
                        "provider", cliProxyFallback.getProvider(), "status", "success").increment();
                    return new RebuttalResponse(fallbackRebuttal, null, fallbackResult.model(), totalElapsed);
                } catch (Exception fallbackEx) {
                    registry.counter("rebuttal.fallback.total",
                        "provider", cliProxyFallback.getProvider(), "status", "fail").increment();
                    log.error("[Rebuttal] CLI 폴백도 실패 — {} | {}", label, fallbackEx.getMessage());
                }
            }

            registry.counter("rebuttal.requests.total", "status", "fail").increment();
            // 반박 실패는 리뷰에 영향을 주지 않음 — null 반환
            return new RebuttalResponse(null, null, null, elapsed);
        }
    }

    private void logRebuttal(RebuttalRequest req, String rebuttal, RebuttalResponse.Usage usage,
                             String model, long elapsedMs) {
        try {
            LocalDateTime now = LocalDateTime.now();
            Path dir = Path.of(logDir).resolve(now.format(DIR_FMT));
            Files.createDirectories(dir);

            String safeRepo = req.repo().replace('/', '-');
            String filename = "%s-%s-PR%d-rebuttal.md".formatted(now.format(FILE_FMT), safeRepo, req.prNumber());
            Path file = dir.resolve(filename);

            StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("timestamp: \"").append(now).append("\"\n");
            sb.append("type: \"rebuttal\"\n");
            sb.append("repo: \"").append(req.repo()).append("\"\n");
            sb.append("prNumber: ").append(req.prNumber()).append("\n");
            sb.append("model: \"").append(model != null ? model : "").append("\"\n");
            if (usage != null) {
                sb.append("inputTokens: ").append(usage.inputTokens()).append("\n");
                sb.append("outputTokens: ").append(usage.outputTokens()).append("\n");
            }
            sb.append("elapsedMs: ").append(elapsedMs).append("\n");
            sb.append("hasRebuttal: ").append(rebuttal != null).append("\n");
            sb.append("---\n\n");

            if (rebuttal != null) {
                sb.append("# Rebuttal\n\n").append(rebuttal).append("\n");
            } else {
                sb.append("# No Rebuttal Generated\n");
            }

            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            log.info("[RebuttalLog] 저장 완료: {}", file);
        } catch (Exception e) {
            log.error("[RebuttalLog] 저장 실패: {}", e.getMessage(), e);
        }
    }

}
