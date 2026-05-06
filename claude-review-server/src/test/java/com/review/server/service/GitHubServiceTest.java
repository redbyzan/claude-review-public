package com.review.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import com.review.server.config.GitHubConfig;
import com.review.server.service.GitHubService.CommentResult;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class GitHubServiceTest {

    @Mock
    private RestClient restClient;

    private GitHubConfig config;
    private SimpleMeterRegistry registry;
    private GitHubService service;

    @BeforeEach
    void setUp() {
        config = new GitHubConfig("test-pat-token", "https://api.github.com", "owner/repo1,owner/repo2");
        registry = new SimpleMeterRegistry();
        service = new GitHubService(restClient, config, registry);
    }

    @Test
    void isAvailable_trueWhenPatSet() {
        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void isAvailable_falseWhenPatEmpty() {
        GitHubConfig emptyConfig = new GitHubConfig("", "https://api.github.com", "");
        GitHubService emptyService = new GitHubService(restClient, emptyConfig, registry);
        assertThat(emptyService.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_falseWhenPatNull() {
        GitHubConfig nullConfig = new GitHubConfig(null, "https://api.github.com", "");
        GitHubService nullService = new GitHubService(restClient, nullConfig, registry);
        assertThat(nullService.isAvailable()).isFalse();
    }

    @Test
    void isRepoAllowed_trueWhenInWhitelist() {
        assertThat(service.isRepoAllowed("owner/repo1")).isTrue();
        assertThat(service.isRepoAllowed("owner/repo2")).isTrue();
    }

    @Test
    void isRepoAllowed_falseWhenNotInWhitelist() {
        assertThat(service.isRepoAllowed("other/repo")).isFalse();
    }

    @Test
    void isRepoAllowed_trueWhenWhitelistEmpty() {
        GitHubConfig openConfig = new GitHubConfig("pat", "https://api.github.com", "");
        GitHubService openService = new GitHubService(restClient, openConfig, registry);
        assertThat(openService.isRepoAllowed("any/repo")).isTrue();
    }

    @Test
    void postComment_unavailableWhenPatNotSet() {
        GitHubConfig noPatConfig = new GitHubConfig("", "https://api.github.com", "owner/repo1");
        GitHubService noPatService = new GitHubService(restClient, noPatConfig, registry);
        CommentResult result = noPatService.postComment("owner/repo1", 1, "body");
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("PAT not configured");
    }

    @Test
    void postComment_deniedWhenRepoNotAllowed() {
        CommentResult result = service.postComment("other/repo", 1, "body");
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not allowed");
    }

    @Test
    void postComment_deniedWhenRepoInvalid() {
        CommentResult result = service.postComment("invalid", 1, "body");
        assertThat(result.success()).isFalse();
    }

    @Test
    void getComments_emptyWhenPatNotSet() {
        GitHubConfig noPatConfig = new GitHubConfig("", "https://api.github.com", "owner/repo1");
        GitHubService noPatService = new GitHubService(restClient, noPatConfig, registry);
        List<?> comments = noPatService.getComments("owner/repo1", 1);
        assertThat(comments).isEmpty();
    }

    @Test
    void getComments_emptyWhenRepoNotAllowed() {
        List<?> comments = service.getComments("other/repo", 1);
        assertThat(comments).isEmpty();
    }

    @Test
    void addReaction_falseWhenPatNotSet() {
        GitHubConfig noPatConfig = new GitHubConfig("", "https://api.github.com", "");
        GitHubService noPatService = new GitHubService(restClient, noPatConfig, registry);
        assertThat(noPatService.addReaction("owner/repo1", 1L, "eyes")).isFalse();
    }

    @Test
    void getPullRequestDiff_nullWhenPatNotSet() {
        GitHubConfig noPatConfig = new GitHubConfig("", "https://api.github.com", "");
        GitHubService noPatService = new GitHubService(restClient, noPatConfig, registry);
        assertThat(noPatService.getPullRequestDiff("owner/repo1", 1)).isNull();
    }

    @Test
    void commentResult_successFactory() {
        CommentResult result = CommentResult.success(123L, "https://github.com/test");
        assertThat(result.success()).isTrue();
        assertThat(result.commentId()).isEqualTo(123L);
        assertThat(result.url()).isEqualTo("https://github.com/test");
        assertThat(result.error()).isNull();
    }

    @Test
    void commentResult_failedFactory() {
        CommentResult result = CommentResult.failed("error msg");
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("error msg");
    }
}
