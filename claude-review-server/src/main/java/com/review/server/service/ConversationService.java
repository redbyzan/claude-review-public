package com.review.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import com.review.server.dto.ConversationRequest;
import com.review.server.dto.ConversationResponse;
import com.review.server.prompt.PromptProvider;
import com.review.server.prompt.PromptProvider.ConversationPromptContext;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ChatClient chatClient;
    private final PromptProvider promptProvider;
    private final ReviewLogService reviewLogService;
    private final CliProxyFallbackService cliProxyFallback;
    private final MeterRegistry registry;

    private final Counter inputTokenCounter;
    private final Counter outputTokenCounter;
    private final Timer conversationTimer;

    public ConversationService(ChatClient.Builder chatClientBuilder, PromptProvider promptProvider,
                               ReviewLogService reviewLogService, CliProxyFallbackService cliProxyFallback,
                               MeterRegistry registry) {
        this.chatClient = chatClientBuilder.build();
        this.promptProvider = promptProvider;
        this.reviewLogService = reviewLogService;
        this.cliProxyFallback = cliProxyFallback;
        this.registry = registry;

        this.inputTokenCounter = Counter.builder("review.conversation.tokens").tag("type", "input").register(registry);
        this.outputTokenCounter = Counter.builder("review.conversation.tokens").tag("type", "output").register(registry);
        this.conversationTimer = Timer.builder("review.conversation.duration")
            .description("AI conversation response time").register(registry);
    }

    public ConversationResponse respond(ConversationRequest request) {
        String label = "%s#%d".formatted(request.repo(), request.prNumber());

        // 질문이 의미 있는 내용이면 리뷠 코멘트 유무와 관계없이 AI 응답 생성
        boolean hasSubstantiveQuestion = request.question() != null
            && request.question().trim().length() > 20;

        if (!hasSubstantiveQuestion && !request.hasReviewComments()) {
            log.info("[Conversation] NO_REVIEW — {} | guiding to conversation", label);
            return guideToConversation();
        }

        log.info("[Conversation] CHAT_MODE — {} | author:{} | question length:{} | hasReview:{}",
            label, request.prAuthor(), request.question().length(), request.hasReviewComments());
        return generateConversation(request);
    }

    private ConversationResponse guideToConversation() {
        String message = "전체 리뷰는 PR을 업데이트(push)하면 자동으로 제공됩니다. "
            + "궁금한 부분에 대해 멘션으로 질문을 남겨 보세요.";
        return new ConversationResponse(message, null, null, 0);
    }

    private ConversationResponse generateConversation(ConversationRequest request) {
        String label = "%s#%d".formatted(request.repo(), request.prNumber());
        ConversationPromptContext ctx = promptProvider.buildConversationContext(request);
        long start = System.currentTimeMillis();

        try {
            ChatResponse chatResponse = chatClient.prompt()
                .system(ctx.systemPrompt())
                .user(ctx.userPrompt())
                .call()
                .chatResponse();

            String content = chatResponse.getResult().getOutput().getText();
            long elapsed = System.currentTimeMillis() - start;

            ConversationResponse.Usage usage = extractUsage(chatResponse);
            String model = extractModel(chatResponse);

            content = "## 💬 AI 답변\n\n" + content;
            ConversationResponse response = new ConversationResponse(content, usage, model, elapsed);

            reviewLogService.logConversation(request, response, ctx);

            recordMetrics("conversation", ctx.personaName(), model, usage, elapsed);
            log.info("[Conversation] CHAT_MODE DONE — {} | {}ms | persona:{}", label, elapsed, ctx.personaName());

            return response;

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[Conversation] CHAT_MODE FAIL — {} | {}ms | {}", label, elapsed, e.getMessage());

            if (cliProxyFallback.isEnabled()) {
                try {
                    var fallbackResponse = cliProxyFallback.review(ctx.systemPrompt(), ctx.userPrompt());
                    String replyWithHeader = "## 💬 AI 답변\n\n" + fallbackResponse.review();
                    ConversationResponse convResponse = new ConversationResponse(
                        replyWithHeader, toConversationUsage(fallbackResponse.usage()), fallbackResponse.model(),
                        elapsed + fallbackResponse.elapsedMs());
                    registry.counter("review.conversation.requests.total", "status", "success",
                        "mode", "conversation", "model", "cli-fallback").increment();
                    return convResponse;
                } catch (Exception fallbackEx) {
                    log.error("[Conversation] CLI fallback also failed — {} | {}", label, fallbackEx.getMessage());
                }
            }

            registry.counter("review.conversation.requests.total", "status", "fail",
                "mode", "conversation").increment();
            throw new RuntimeException("AI 대화 응답 실패: " + e.getMessage(), e);
        }
    }

    private ConversationResponse.Usage extractUsage(ChatResponse chatResponse) {
        var metadata = chatResponse.getMetadata();
        if (metadata != null) {
            var tokenUsage = metadata.getUsage();
            if (tokenUsage != null) {
                return new ConversationResponse.Usage(
                    (long) tokenUsage.getPromptTokens(),
                    (long) tokenUsage.getCompletionTokens()
                );
            }
        }
        return null;
    }

    private String extractModel(ChatResponse chatResponse) {
        var metadata = chatResponse.getMetadata();
        return metadata != null ? metadata.getModel() : null;
    }

    private void recordMetrics(String mode, String persona, String model,
                               ConversationResponse.Usage usage, long elapsed) {
        registry.counter("review.conversation.requests.total", "status", "success",
            "mode", mode, "persona", persona,
            "model", model != null ? model : "unknown").increment();
        conversationTimer.record(elapsed, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (usage != null) {
            if (usage.inputTokens() != null) inputTokenCounter.increment(usage.inputTokens());
            if (usage.outputTokens() != null) outputTokenCounter.increment(usage.outputTokens());
        }
    }

    private ConversationResponse.Usage toConversationUsage(com.review.server.dto.ReviewResponse.Usage usage) {
        if (usage == null) return null;
        return new ConversationResponse.Usage(usage.inputTokens(), usage.outputTokens());
    }
}
