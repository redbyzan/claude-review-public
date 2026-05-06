package com.review.server.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.review.server.dto.ConversationRequest;
import com.review.server.dto.ConversationResponse;
import com.review.server.dto.ReviewRequest;
import com.review.server.dto.ReviewResponse;
import com.review.server.prompt.PromptProvider.ConversationPromptContext;
import com.review.server.prompt.PromptProvider.PromptContext;

@Service
public class ReviewLogService {

    private static final Logger log = LoggerFactory.getLogger(ReviewLogService.class);
    private static final DateTimeFormatter DIR_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Value("${review.log-dir:/app/review-logs}")
    private String logDir;

    @Async
    public void logReview(ReviewRequest request, ReviewResponse response, PromptContext ctx, boolean ctaIncluded) {
        try {
            LocalDateTime now = LocalDateTime.now();
            Path dir = Path.of(logDir).resolve(now.format(DIR_FMT));
            Files.createDirectories(dir);

            String safeRepo = request.repo().replace('/', '-');
            String filename = "%s-%s-PR%d.md".formatted(now.format(FILE_FMT), safeRepo, request.prNumber());
            Path file = dir.resolve(filename);

            String content = buildMarkdown(request, response, ctx, now, ctaIncluded);
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

            log.info("[ReviewLog] 저장 완료: {}", file);
        } catch (Exception e) {
            log.error("[ReviewLog] 저장 실패: {}", e.getMessage(), e);
        }
    }

    private String buildMarkdown(ReviewRequest req, ReviewResponse res, PromptContext ctx, LocalDateTime ts, boolean ctaIncluded) {
        Long inputTokens = res.usage() != null ? res.usage().inputTokens() : null;
        Long outputTokens = res.usage() != null ? res.usage().outputTokens() : null;

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("timestamp: \"").append(ts).append("\"\n");
        sb.append("repo: \"").append(req.repo()).append("\"\n");
        sb.append("prNumber: ").append(req.prNumber()).append("\n");
        sb.append("prTitle: \"").append(escapeYaml(req.prTitle())).append("\"\n");
        sb.append("prAuthor: \"").append(escapeYaml(req.prAuthor())).append("\"\n");
        sb.append("baseBranch: \"").append(req.baseBranch()).append("\"\n");
        sb.append("persona: \"").append(ctx.personaTone()).append("\"\n");
        sb.append("model: \"").append(res.model() != null ? res.model() : "").append("\"\n");
        if (inputTokens != null) sb.append("inputTokens: ").append(inputTokens).append("\n");
        if (outputTokens != null) sb.append("outputTokens: ").append(outputTokens).append("\n");
        sb.append("elapsedMs: ").append(res.elapsedMs()).append("\n");
        sb.append("diffSize: ").append(ctx.originalDiffLength()).append("\n");
        sb.append("diffTruncated: ").append(ctx.diffTruncated()).append("\n");
        sb.append("ctaIncluded: ").append(ctaIncluded).append("\n");
        sb.append("---\n\n");

        sb.append("# Code Review: ").append(req.repo()).append(" #").append(req.prNumber()).append("\n\n");
        sb.append("> **PR**: ").append(escapeYaml(req.prTitle())).append("\n");
        sb.append("> **Author**: ").append(escapeYaml(req.prAuthor()));
        sb.append(" | **Persona**: ").append(ctx.personaTone());
        sb.append(" | **Model**: ").append(res.model() != null ? res.model() : "unknown").append("\n");
        sb.append("> **Tokens**: ");
        if (inputTokens != null && outputTokens != null) {
            sb.append(inputTokens).append(" in / ").append(outputTokens).append(" out");
        } else {
            sb.append("N/A");
        }
        sb.append(" | **Duration**: ").append(res.elapsedMs()).append("ms\n\n");

        sb.append("---\n\n");
        sb.append(res.review()).append("\n\n");
        sb.append("---\n\n");
        sb.append("<details><summary>Raw Diff (").append(ctx.originalDiffLength()).append(" bytes)</summary>\n\n");
        sb.append("```diff\n").append(req.diff()).append("\n```\n\n");
        sb.append("</details>\n");

        return sb.toString();
    }

    private String escapeYaml(String value) {
        if (value == null) return "";
        return value.replace("\"", "\\\"");
    }

    @Async
    public void logConversation(ConversationRequest request, ConversationResponse response, ConversationPromptContext ctx) {
        try {
            LocalDateTime now = LocalDateTime.now();
            Path dir = Path.of(logDir).resolve(now.format(DIR_FMT));
            Files.createDirectories(dir);

            String safeRepo = request.repo().replace('/', '-');
            String filename = "%s-%s-PR%d-conv.md".formatted(now.format(FILE_FMT), safeRepo, request.prNumber());
            Path file = dir.resolve(filename);

            String content = buildConversationMarkdown(request, response, ctx, now);
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

            log.info("[ReviewLog] 대화 로그 저장 완료: {}", file);
        } catch (Exception e) {
            log.error("[ReviewLog] 대화 로그 저장 실패: {}", e.getMessage(), e);
        }
    }

    private String buildConversationMarkdown(ConversationRequest req, ConversationResponse res,
                                              ConversationPromptContext ctx, LocalDateTime ts) {
        Long inputTokens = res.usage() != null ? res.usage().inputTokens() : null;
        Long outputTokens = res.usage() != null ? res.usage().outputTokens() : null;

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("timestamp: \"").append(ts).append("\"\n");
        sb.append("type: \"conversation\"\n");
        sb.append("repo: \"").append(req.repo()).append("\"\n");
        sb.append("prNumber: ").append(req.prNumber()).append("\n");
        sb.append("prTitle: \"").append(escapeYaml(req.prTitle())).append("\"\n");
        sb.append("prAuthor: \"").append(escapeYaml(req.prAuthor())).append("\"\n");
        sb.append("commentType: \"").append(req.commentType() != null ? req.commentType() : "").append("\"\n");
        sb.append("persona: \"").append(ctx.personaTone()).append("\"\n");
        sb.append("model: \"").append(res.model() != null ? res.model() : "").append("\"\n");
        if (inputTokens != null) sb.append("inputTokens: ").append(inputTokens).append("\n");
        if (outputTokens != null) sb.append("outputTokens: ").append(outputTokens).append("\n");
        sb.append("elapsedMs: ").append(res.elapsedMs()).append("\n");
        sb.append("---\n\n");

        sb.append("# Conversation: ").append(req.repo()).append(" #").append(req.prNumber()).append("\n\n");
        sb.append("> **PR**: ").append(escapeYaml(req.prTitle())).append("\n");
        sb.append("> **Author**: ").append(escapeYaml(req.prAuthor()));
        sb.append(" | **Persona**: ").append(ctx.personaName());
        sb.append(" (").append(ctx.personaTone()).append(")");
        sb.append(" | **Model**: ").append(res.model() != null ? res.model() : "unknown").append("\n\n");

        sb.append("## 질문\n\n").append(req.question()).append("\n\n");
        sb.append("---\n\n");
        sb.append("## 답변\n\n").append(res.reply()).append("\n\n");

        if (req.hasReviewComments()) {
            sb.append("---\n\n");
            sb.append("<details><summary>이전 리뷰 코멘트</summary>\n\n");
            sb.append(req.reviewComments()).append("\n\n");
            sb.append("</details>\n\n");
        }

        if (req.diff() != null && !req.diff().isBlank()) {
            sb.append("<details><summary>Raw Diff</summary>\n\n");
            sb.append("```diff\n").append(req.diff()).append("\n```\n\n");
            sb.append("</details>\n");
        }

        return sb.toString();
    }
}
