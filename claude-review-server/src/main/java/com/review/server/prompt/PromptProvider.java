package com.review.server.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.review.server.dto.ConversationRequest;
import com.review.server.dto.ReviewRequest;

@Component
public class PromptProvider {

    private static final Logger log = LoggerFactory.getLogger(PromptProvider.class);

    private List<String> systemPrompts;
    private String conversationPrompt;
    private String rebuttalPrompt;
    private String assignmentRequirements;
    private final AtomicInteger index = new AtomicInteger(0);

    public record PersonaSummary(String name, String description, String tone, String toneLabel, String icon) {}

    public record PromptContext(
        String systemPrompt,
        String userPrompt,
        String personaName,
        String personaTone,
        boolean diffTruncated,
        int originalDiffLength
    ) {}

    public record ConversationPromptContext(
        String systemPrompt,
        String userPrompt,
        String personaName,
        String personaTone,
        String personaIcon
    ) {}

    public List<PersonaSummary> getPersonaSummaries() {
        return List.of(
            new PersonaSummary("10년차 시니어 멘토", "학습과 성장을 돕는 따뜻한 코드 리뷰어", "mentor", "멘토 모드", "🌱"),
            new PersonaSummary("15년차 시니컬 원칙주의자", "타협 없는 엄격한 코드 리뷰어", "cynic", "시니컬 모드", "🔥")
        );
    }

    @PostConstruct
    public void init() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();

        // 리뷰 프롬프트만 로드 (라운드로빈 대상)
        Resource[] reviewResources = resolver.getResources("classpath:prompts/system-review*.md");
        this.systemPrompts = Arrays.stream(reviewResources)
            .sorted(Comparator.comparing(Resource::getFilename))
            .map(r -> {
                try {
                    return r.getContentAsString(StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new RuntimeException("프롬프트 파일 로드 실패: " + r.getFilename(), e);
                }
            })
            .toList();

        if (systemPrompts.isEmpty()) {
            throw new IllegalStateException("시스템 프롬프트가 로드되지 않았습니다.");
        }
        log.info("[Prompt] {}개 리뷰 프롬프트 로드 완료", systemPrompts.size());

        // 대화 프롬프트 별도 로드
        Resource[] convResources = resolver.getResources("classpath:prompts/system-conversation*.md");
        if (convResources.length > 0) {
            this.conversationPrompt = convResources[0].getContentAsString(StandardCharsets.UTF_8);
            log.info("[Prompt] 대화 프롬프트 로드 완료");
        }

        // 반박 프롬프트 별도 로드
        Resource[] rebuttalResources = resolver.getResources("classpath:prompts/system-rebuttal*.md");
        if (rebuttalResources.length > 0) {
            this.rebuttalPrompt = rebuttalResources[0].getContentAsString(StandardCharsets.UTF_8);
            log.info("[Prompt] 반박 프롬프트 로드 완료");
        }

        // 과제 요구사항 로드
        Resource[] reqResources = resolver.getResources("classpath:prompts/requirements*.md");
        if (reqResources.length > 0) {
            this.assignmentRequirements = reqResources[0].getContentAsString(StandardCharsets.UTF_8);
            log.info("[Prompt] 과제 요구사항 로드 완료 ({}자)", assignmentRequirements.length());
        }
    }

    public String getSystemPrompt() {
        int i = Math.floorMod(index.getAndIncrement(), systemPrompts.size());
        log.debug("[Prompt] {}/{} 선택됨", i + 1, systemPrompts.size());
        return systemPrompts.get(i);
    }

    public String buildUserPrompt(ReviewRequest request) {
        return buildPromptContext(request).userPrompt();
    }

    public String getRebuttalPrompt() {
        return rebuttalPrompt;
    }

    public String buildRebuttalUserPrompt(String reviewText, String diff) {
        int diffLimit = 5000;
        String diffSlice = diff;
        if (diff != null && diff.length() > diffLimit) {
            int cutIndex = diff.lastIndexOf('\n', diffLimit);
            if (cutIndex <= 0) cutIndex = diffLimit;
            diffSlice = diff.substring(0, cutIndex);
        }

        return """
            다음 AI 코드 리뷰를 검증하세요.

            <review>
            %s
            </review>

            <diff>
            %s
            </diff>
            """.formatted(reviewText, diffSlice != null ? diffSlice : "");
    }

    public ConversationPromptContext buildConversationContext(ConversationRequest request) {
        int i = Math.floorMod(index.getAndIncrement(), systemPrompts.size());
        log.debug("[Prompt:Conversation] {}/{} 페르소나 선택됨", i + 1, systemPrompts.size());

        PersonaSummary persona = getPersonaSummaries().get(i);

        // 대화 프롬프트에 페르소나 정보 주입
        String systemPrompt = conversationPrompt + "\n\n## 현재 페르소나\n"
            + "당신은 지금 **%s** (%s) 모드입니다. 이 페르소나의 성격과 어조를 유지하면서 답변하세요.\n".formatted(persona.name(), persona.toneLabel());

        // 과제 요구사항이 있으면 대화 프롬프트에도 주입
        if (assignmentRequirements != null && !assignmentRequirements.isBlank()) {
            systemPrompt += "\n## 과제 요구사항\n\n"
                + "이 PR은 CH5 플러스 프로젝트 과제의 일환입니다. 질문에 답변할 때 과제 요구사항을 참고하세요.\n\n"
                + assignmentRequirements;
        }

        // diff 잘라내기
        String diff = request.diff();
        int diffLimit = 50000;
        boolean diffTruncated = diff != null && diff.length() > diffLimit;
        String diffSlice = diff;
        if (diffTruncated) {
            int cutIndex = diff.lastIndexOf('\n', diffLimit);
            if (cutIndex <= 0) cutIndex = diffLimit;
            diffSlice = diff.substring(0, cutIndex);
        }

        // 기존 봇 리뷰 코멘트 섹션
        String reviewSection = "";
        if (request.hasReviewComments()) {
            reviewSection = """

                ---
                이전 AI 리뷰 코멘트:
                %s
                """.formatted(request.reviewComments());
        }

        // 대화 히스토리 섹션
        String historySection = "";
        if (request.hasConversationHistory()) {
            historySection = """

                ---
                이전 대화 히스토리:
                %s
                """.formatted(request.conversationHistory());
        }

        String userPrompt = """
            PR 정보:
            - 저장소: %s
            - PR #%d: %s
            - 작성자: %s
            %s
            ```diff
            %s
            ```
            %s
            %s
            ---
            개발자 질문:
            %s
            """.formatted(
                request.repo(),
                request.prNumber(),
                request.prTitle(),
                request.prAuthor(),
                diffTruncated ? "- ⚠️ diff가 %,d자 중 앞부분 %,d자만 포함합니다.\n".formatted(diff.length(), diffSlice.length()) : "",
                diffSlice != null ? diffSlice : "",
                reviewSection,
                historySection,
                request.question()
            );

        return new ConversationPromptContext(systemPrompt, userPrompt, persona.name(), persona.tone(), persona.icon());
    }

    public PromptContext buildPromptContext(ReviewRequest request) {
        int i = Math.floorMod(index.getAndIncrement(), systemPrompts.size());
        log.debug("[Prompt] {}/{} 선택됨", i + 1, systemPrompts.size());

        String systemPrompt = systemPrompts.get(i);

        // 과제 요구사항이 있으면 system prompt에 주입
        if (assignmentRequirements != null && !assignmentRequirements.isBlank()) {
            systemPrompt = systemPrompt + "\n\n## 과제 요구사항\n\n"
                + "이 PR은 **CH5 플러스 프로젝트 (커머스 플랫폼)** 과제의 일환입니다.\n"
                + "아래 과제 요구사항을 기준으로 1차 리뷰를 작성하세요.\n"
                + "특히 **사용 금지 기술(Redisson 등)**을 사용한 경우 반드시 지적하세요.\n"
                + "도전 기능은 코드에서 감지되는 경우에만 리뷰하고, 감지되지 않으면 언급하지 마세요.\n\n"
                + assignmentRequirements;
        }
        PersonaSummary persona = getPersonaSummaries().get(i);

        String diff = request.diff();
        int originalDiffLength = diff.length();
        int diffLimit = 50000;
        boolean diffTruncated = originalDiffLength > diffLimit;
        String diffSlice = diff;
        if (diffTruncated) {
            int cutIndex = diff.lastIndexOf('\n', diffLimit);
            if (cutIndex <= 0) cutIndex = diffLimit;
            diffSlice = diff.substring(0, cutIndex);
        }

        String previousSection = "";
        if (request.hasPreviousReviews()) {
            previousSection = """

                ---
                이전 AI 리뷰 피드백 (최근 리뷰):
                %s

                위 이전 리뷰에서 지적한 사항이 현재 diff에 반영되었는지 검증해주세요.
                """.formatted(request.previousReviews());
        }

        String userPrompt = """
            PR 정보:
            - 저장소: %s
            - PR #%d: %s
            - 작성자: %s
            - 베이스 브랜치: %s
            %s
            ```diff
            %s
            ```
            %s
            """.formatted(
                request.repo(),
                request.prNumber(),
                request.prTitle(),
                request.prAuthor(),
                request.baseBranch(),
                diffTruncated ? "- ⚠️ diff가 %,d자 중 앞부분 %,d자만 리뷰합니다. 누락된 변경사항이 있을 수 있습니다.\n".formatted(originalDiffLength, diffSlice.length()) : "",
                diffSlice,
                previousSection
            );

        return new PromptContext(systemPrompt, userPrompt, persona.name(), persona.tone(), diffTruncated, originalDiffLength);
    }
}
