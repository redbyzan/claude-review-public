package com.review.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class MonitorServiceTest {

    private SimpleMeterRegistry registry;
    private MonitorService monitorService;

    @Mock
    private SlackService slackService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        lenient().when(slackService.isEnabled()).thenReturn(true);
        monitorService = new MonitorService(registry, slackService);
    }

    @Nested
    class RecordReviewComplete {
        @Test
        void recordsSuccessCounterWithTags() {
            monitorService.recordReviewComplete("owner/repo", "mentor", "claude-sonnet",
                    true, true, 1500L, 100, 50);

            assertThat(registry.counter("review.metadata", "status", "success",
                    "team", "repo", "persona", "mentor", "model", "claude-sonnet",
                    "cta_included", "true", "has_requirements", "true").count()).isEqualTo(1.0);
        }

        @Test
        void recordsTokenCounters() {
            monitorService.recordReviewComplete("owner/repo", "mentor", "claude-sonnet",
                    true, true, 1500L, 100, 50);

            assertThat(registry.counter("review.tokens", "type", "input", "model", "claude-sonnet").count()).isEqualTo(100.0);
            assertThat(registry.counter("review.tokens", "type", "output", "model", "claude-sonnet").count()).isEqualTo(50.0);
        }

        @Test
        void resetsConsecutiveFailures() {
            monitorService.recordReviewError("owner/repo", "timeout", "API timeout");
            assertThat(monitorService.getConsecutiveFailures()).isEqualTo(1);

            monitorService.recordReviewComplete("owner/repo", "mentor", "model",
                    true, true, 1000L, 10, 5);
            assertThat(monitorService.getConsecutiveFailures()).isZero();
        }
    }

    @Nested
    class RecordReviewError {
        @Test
        void recordsFailCounterWithErrorCode() {
            monitorService.recordReviewError("owner/repo", "timeout", "API timeout");

            assertThat(registry.counter("review.metadata", "status", "fail",
                    "team", "repo", "error", "timeout").count()).isEqualTo(1.0);
        }

        @Test
        void incrementsConsecutiveFailures() {
            monitorService.recordReviewError("owner/repo", "timeout", "API timeout");
            monitorService.recordReviewError("owner/repo", "timeout", "API timeout");

            assertThat(monitorService.getConsecutiveFailures()).isEqualTo(2);
        }
    }

    @Nested
    class RecordAuthBlocked {
        @Test
        void recordsAuthBlockedCounter() {
            monitorService.recordAuthBlocked("stranger/repo", java.util.List.of("invalid_secret"));

            assertThat(registry.counter("auth.blocked.total", "reason", "invalid_secret").count()).isEqualTo(1.0);
        }

        @Test
        void normalizesUnknownReasonToOther() {
            monitorService.recordAuthBlocked("stranger/repo", java.util.List.of("malicious_payload_${jndi:ldap://evil}"));

            assertThat(registry.counter("auth.blocked.total", "reason", "other").count()).isEqualTo(1.0);
        }
    }

    @Nested
    class RecordRateLimitHit {
        @Test
        void recordsRateLimitCounter() {
            monitorService.recordRateLimitHit("/review");

            assertThat(registry.counter("rate.limit.blocked.total").count()).isEqualTo(1.0);
        }
    }

    @Nested
    class RecordRebuttalResult {
        @Test
        void recordsRebuttalResult() {
            monitorService.recordRebuttalResult(true);
            monitorService.recordRebuttalResult(false);

            assertThat(registry.counter("rebuttal.results.total", "has_rebuttal", "true").count()).isEqualTo(1.0);
            assertThat(registry.counter("rebuttal.results.total", "has_rebuttal", "false").count()).isEqualTo(1.0);
        }
    }

    @Nested
    class RecordConversationResult {
        @Test
        void recordsConversationResult() {
            monitorService.recordConversationResult(true);
            monitorService.recordConversationResult(false);

            assertThat(registry.counter("conversation.results.total", "status", "success").count()).isEqualTo(1.0);
            assertThat(registry.counter("conversation.results.total", "status", "fail").count()).isEqualTo(1.0);
        }
    }

    @Nested
    class RecordLeaderboardRefresh {
        @Test
        void recordsLeaderboardRefresh() {
            monitorService.recordLeaderboardRefresh(true);
            monitorService.recordLeaderboardRefresh(false);

            assertThat(registry.counter("leaderboard.refresh", "status", "success").count()).isEqualTo(1.0);
            assertThat(registry.counter("leaderboard.refresh", "status", "fail").count()).isEqualTo(1.0);
        }
    }

    @Nested
    class TeamTagExtraction {
        @Test
        void extractsRepoNameFromOwnerRepo() {
            monitorService.recordReviewComplete("your-username/claude-review", "mentor", "model",
                    true, true, 1000L, 10, 5);

            assertThat(registry.counter("review.metadata", "status", "success",
                    "team", "claude-review", "persona", "mentor", "model", "model",
                    "cta_included", "true", "has_requirements", "true").count()).isEqualTo(1.0);
        }

        @Test
        void handlesNullRepo() {
            monitorService.recordReviewComplete(null, "mentor", "model",
                    true, true, 1000L, 10, 5);

            assertThat(registry.counter("review.metadata", "status", "success",
                    "team", "unknown", "persona", "mentor", "model", "model",
                    "cta_included", "true", "has_requirements", "true").count()).isEqualTo(1.0);
        }
    }
}
