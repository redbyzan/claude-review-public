package com.review.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.List;

import org.springframework.test.web.servlet.MockMvc;

import com.review.server.dto.ReviewResponse;
import com.review.server.prompt.PromptProvider;
import com.review.server.service.ChangelogService;
import com.review.server.service.ChangelogService.ChangelogEntry;
import com.review.server.service.ConversationService;
import com.review.server.service.LeaderboardService;
import com.review.server.service.LeaderboardService.TotalStats;
import com.review.server.service.GitHubService;
import com.review.server.service.MonitorService;
import com.review.server.service.RebuttalService;
import com.review.server.service.ReviewService;
import com.review.server.service.SlackService;
import com.review.server.service.StatsService;

@WebMvcTest
@ActiveProfiles("test")
class ControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ChatClient.Builder chatClientBuilder;
    @MockitoBean ReviewService reviewService;
    @MockitoBean RebuttalService rebuttalService;
    @MockitoBean ConversationService conversationService;
    @MockitoBean SlackService slackService;
    @MockitoBean GitHubService gitHubService;
    @MockitoBean LeaderboardService leaderboardService;
    @MockitoBean ChangelogService changelogService;
    @MockitoBean PromptProvider promptProvider;
    @MockitoBean MonitorService monitorService;
    @MockitoBean StatsService statsService;

    @BeforeEach
    void setUp() {
        when(promptProvider.getPersonaSummaries()).thenReturn(List.of(
            new PromptProvider.PersonaSummary("test", "desc", "mentor", "멘토", "🌱")
        ));
        when(changelogService.getRecentPublicEntries(3)).thenReturn(List.of(
            new ChangelogEntry("2026-01-01", "test", "Test Entry", "desc", null, null, null, "feature", false)
        ));
        when(changelogService.getPublicEntries()).thenReturn(List.of(
            new ChangelogEntry("2026-01-01", "test", "Test Entry", "desc", null, null, null, "feature", false)
        ));
        when(leaderboardService.getTopTeams(3)).thenReturn(List.of());
        when(leaderboardService.getTotalStats()).thenReturn(new TotalStats(0, 0, 0));
        when(leaderboardService.getLeaderboard()).thenReturn(List.of());
        when(slackService.isEnabled()).thenReturn(false);

        var snapshot = new StatsService.StatsSnapshot(0, 0, 100.0, 0, 0, 0,
            0, 0, java.util.Map.of(), 0, 0, 0.0, 0, 0, 0, 0, 0,
            java.math.BigDecimal.ZERO,
            java.time.LocalDateTime.now());
        when(statsService.getSnapshot()).thenReturn(snapshot);
    }

    @Nested
    class HealthTests {
        @Test
        void healthReturns200() throws Exception {
            mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
        }
    }

    @Nested
    class HomeTests {
        @Test
        void homePageReturns200() throws Exception {
            mvc.perform(get("/"))
                .andExpect(status().isOk());
        }

        @Test
        void releasePageReturns200() throws Exception {
            mvc.perform(get("/release"))
                .andExpect(status().isOk());
        }

        @Test
        void releaseDetailRedirectsForUnknownSlug() throws Exception {
            mvc.perform(get("/release/nonexistent"))
                .andExpect(status().is3xxRedirection());
        }

        @Test
        void incidentPageReturns200() throws Exception {
            mvc.perform(get("/incident"))
                .andExpect(status().isOk());
        }

        @Test
        void leaderboardPageReturns200() throws Exception {
            mvc.perform(get("/leaderboard"))
                .andExpect(status().isOk());
        }

        @Test
        void statsPageReturns200() throws Exception {
            mvc.perform(get("/stats"))
                .andExpect(status().isOk());
        }

        @Test
        void ymlDownloadReturns200() throws Exception {
            mvc.perform(get("/ai-review.yml"))
                .andExpect(status().isOk());
        }
    }

    @Nested
    class ReviewTests {
        @Test
        void rejectsShortDiff() throws Exception {
            mvc.perform(post("/review")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"diff\":\"short\"}"))
                .andExpect(status().isBadRequest());
        }

        @Test
        void returnsReview() throws Exception {
            when(reviewService.review(any())).thenReturn(
                new ReviewResponse("LGTM", null, "test-model", 0));

            mvc.perform(post("/review")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"diff\":\"" + "a".repeat(100) + "\",\"pr_title\":\"test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review").value("LGTM"));
        }
    }

    @Nested
    class ConversationTests {
        @Test
        void returnsConversation() throws Exception {
            when(conversationService.respond(any())).thenReturn(
                new com.review.server.dto.ConversationResponse("AI 답변", null, null, 0));

            mvc.perform(post("/review/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"hello\",\"repo\":\"test/repo\",\"pr_number\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("AI 답변"));
        }
    }
}
