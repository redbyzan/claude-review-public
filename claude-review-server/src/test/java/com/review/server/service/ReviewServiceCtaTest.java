package com.review.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import com.review.server.prompt.PromptProvider;

import com.review.server.service.MonitorService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ReviewServiceCtaTest {

    @Nested
    class PromptHasSpartaMention {
        // 프롬프트에서 "생각해보기 + @sparta 유도"를 이미 생성한 경우
        // 하단 CTA는 중복 방지를 위해 생략됨

        @Test
        void skipsBottomCta_whenPromptAlreadyContainsSparta() {
            ReviewService service = serviceWithCta();
            String input = """
                ## 🟢 잘된 점
                좋은 코드입니다.

                ## 💡 학습 포인트
                학습 내용

                ## 🤔 생각해보기
                이 동시성 제어 방식은 트래픽이 10배 늘어나면 어떤 문제가 발생할까요?

                > 💬 이 질문에 대해 궁금한 점이 있으면 코멘트에 **@sparta** 를 남겨보세요!
                > 예: `@sparta 이 부분에서 분산락 대신 낙관적 락을 쓸 수 있나요?`

                ## 🔄 이전 피드백 반영 여부
                반영됨
                """;

            String result = injectCTA(service, input);

            // @sparta는 프롬프트에서 이미 포함
            assertThat(result).contains("@sparta");
            // 하단 CTA는 중복이므로 삽입되지 않음
            assertThat(result).doesNotContain("---\n> 💬 **리뷰에 대해 궁금한 점이 있나요?**");
            // 원본 내용 보존
            assertThat(result).contains("## 🟢 잘된 점");
            assertThat(result).contains("## 🤔 생각해보기");
        }

        @Test
        void cynicSkipsBottomCta_whenPromptContainsSparta() {
            ReviewService service = serviceWithCta();
            String input = """
                ### 💡 학습 포인트
                학습 내용

                ### 🤔 생각해보기
                이 예외 처리 방식은 어떤 연쇄 장애를 유발할까요?

                > 💬 이 질문에 대해 궁금한 점이 있으면 코멘트에 **@sparta** 를 남겨보세요!
                """;

            String result = injectCTA(service, input);

            assertThat(result).contains("@sparta");
            assertThat(result).doesNotContain("---\n> 💬 **리뷰에 대해 궁금한 점이 있나요?**");
        }
    }

    @Nested
    class PromptMissingSpartaMention {
        // AI가 생각해보기 섹션을 누락한 경우, 하단 CTA로 보조 유도

        @Test
        void appendsBottomCta_whenPromptMissingSparta() {
            ReviewService service = serviceWithCta();
            String input = "## 🟡 권장 개선\n개선사항만 있는 간단한 리뷰\n";

            String result = injectCTA(service, input);

            assertThat(result).contains("@sparta");
            assertThat(result).containsPattern("궁금|질문");
            assertThat(result).contains("---");
        }
    }

    @Test
    void ctaDisabled_returnsOriginal() {
        ReviewService service = serviceWithCtaDisabled();
        String input = "## 💡 학습 포인트\n내용\n";

        String result = injectCTA(service, input);

        assertThat(result).isEqualTo(input);
    }

    // --- helpers ---

    private ReviewService serviceWithCta() {
        ReviewService service = createMinimalService();
        ReflectionTestUtils.setField(service, "ctaEnabled", true);
        return service;
    }

    private ReviewService serviceWithCtaDisabled() {
        ReviewService service = createMinimalService();
        ReflectionTestUtils.setField(service, "ctaEnabled", false);
        return service;
    }

    private ReviewService createMinimalService() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return new ReviewService(
            builder,
            mock(PromptProvider.class),
            mock(ReviewLogService.class),
            mock(CliProxyFallbackService.class),
            mock(ChangelogService.class),
            mock(LeaderboardService.class),
            mock(RebuttalService.class),
            mock(MonitorService.class),
            new SimpleMeterRegistry()
        );
    }

    private String injectCTA(ReviewService service, String content) {
        return ReflectionTestUtils.invokeMethod(service, "injectCTA", content);
    }
}
