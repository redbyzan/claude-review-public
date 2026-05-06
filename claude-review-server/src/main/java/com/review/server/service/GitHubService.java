package com.review.server.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.review.server.config.GitHubConfig;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class GitHubService {

    private static final Logger log = LoggerFactory.getLogger(GitHubService.class);
    private static final ParameterizedTypeReference<List<Map<String, Object>>> COMMENT_LIST_TYPE =
        new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final GitHubConfig config;
    private final Counter apiCallCounter;
    private final Counter apiErrorCounter;

    public GitHubService(RestClient gitHubRestClient, GitHubConfig config, MeterRegistry registry) {
        this.restClient = gitHubRestClient;
        this.config = config;
        this.apiCallCounter = Counter.builder("github.api.calls.total")
            .description("GitHub API calls").register(registry);
        this.apiErrorCounter = Counter.builder("github.api.errors.total")
            .description("GitHub API errors").register(registry);
    }

    public boolean isAvailable() {
        return config.isConfigured();
    }

    public boolean isRepoAllowed(String repo) {
        List<String> allowed = config.getAllowedRepoList();
        if (allowed.isEmpty()) return true;
        return allowed.contains(repo);
    }

    public CommentResult postComment(String repo, int prNumber, String body) {
        if (!ensureAvailable("postComment")) return CommentResult.unavailable();
        if (!validateRepo(repo, "postComment")) return CommentResult.denied();

        String url = "/repos/{repo}/issues/{prNumber}/comments";
        apiCallCounter.increment();

        try {
            Map<String, Object> response = restClient.post()
                .uri(url, repo, prNumber)
                .body(Map.of("body", body))
                .retrieve()
                .body(Map.class);

            if (response != null && response.containsKey("id")) {
                long commentId = ((Number) response.get("id")).longValue();
                log.info("[GitHub] 코멘트 등록 성공 — repo:{} pr:{} commentId:{}", repo, prNumber, commentId);
                return CommentResult.success(commentId, (String) response.get("html_url"));
            }
            return CommentResult.success(-1, null);
        } catch (RestClientException e) {
            return handleError("postComment", repo, prNumber, e);
        }
    }

    public List<GithubComment> getComments(String repo, int prNumber) {
        if (!ensureAvailable("getComments")) return Collections.emptyList();
        if (!validateRepo(repo, "getComments")) return Collections.emptyList();

        String url = "/repos/{repo}/issues/{prNumber}/comments";
        apiCallCounter.increment();

        try {
            List<Map<String, Object>> response = restClient.get()
                .uri(url, repo, prNumber)
                .retrieve()
                .body(COMMENT_LIST_TYPE);

            if (response == null) return Collections.emptyList();

            return response.stream()
                .map(this::toGithubComment)
                .toList();
        } catch (RestClientException e) {
            apiErrorCounter.increment();
            log.warn("[GitHub] getComments 실패 — repo:{} pr:{} | {}", repo, prNumber, e.getMessage());
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private GithubComment toGithubComment(Map<String, Object> m) {
        Map<String, Object> userMap = (Map<String, Object>) m.get("user");
        return new GithubComment(
            ((Number) m.get("id")).longValue(),
            (String) m.get("body"),
            (String) userMap.get("login"),
            (String) userMap.get("type"),
            (String) m.get("created_at")
        );
    }

    public boolean addReaction(String repo, long commentId, String content) {
        return addReaction(repo, commentId, content, "issue_comment");
    }

    public boolean addReaction(String repo, long commentId, String content, String commentType) {
        if (!ensureAvailable("addReaction")) return false;

        String url = "pull_request_review_comment".equals(commentType)
            ? "/repos/{repo}/pulls/comments/{commentId}/reactions"
            : "/repos/{repo}/issues/comments/{commentId}/reactions";
        apiCallCounter.increment();

        try {
            restClient.post()
                .uri(url, repo, commentId)
                .body(Map.of("content", content))
                .retrieve()
                .body(Map.class);

            log.info("[GitHub] 리액션 추가 성공 — repo:{} commentId:{} reaction:{}", repo, commentId, content);
            return true;
        } catch (RestClientException e) {
            apiErrorCounter.increment();
            log.warn("[GitHub] addReaction 실패 — repo:{} commentId:{} | {}", repo, commentId, e.getMessage());
            return false;
        }
    }

    public String getPullRequestDiff(String repo, int prNumber) {
        if (!ensureAvailable("getPullRequestDiff")) return null;

        String url = "/repos/{repo}/pulls/{prNumber}";
        apiCallCounter.increment();

        try {
            return restClient.get()
                .uri(url, repo, prNumber)
                .header("Accept", "application/vnd.github.v3.diff")
                .retrieve()
                .body(String.class);
        } catch (RestClientException e) {
            apiErrorCounter.increment();
            log.warn("[GitHub] getPullRequestDiff 실패 — repo:{} pr:{} | {}", repo, prNumber, e.getMessage());
            return null;
        }
    }

    private boolean ensureAvailable(String operation) {
        if (!config.isConfigured()) {
            log.debug("[GitHub] {} 스킵 — PAT 미설정", operation);
            return false;
        }
        return true;
    }

    private boolean validateRepo(String repo, String operation) {
        if (repo == null || !repo.contains("/")) {
            log.warn("[GitHub] {} — 잘못된 repo 형식: {}", operation, repo);
            return false;
        }
        if (!isRepoAllowed(repo)) {
            log.warn("[GitHub] {} — 허용되지 않은 repo: {}", operation, repo);
            return false;
        }
        return true;
    }

    private CommentResult handleError(String operation, String repo, int prNumber, RestClientException e) {
        apiErrorCounter.increment();

        if (e instanceof HttpStatusCodeException httpEx) {
            HttpStatus status = HttpStatus.resolve(httpEx.getStatusCode().value());
            if (status == HttpStatus.FORBIDDEN) {
                log.error("[GitHub] {} — 권한 부족 (403) — repo:{} pr:{} | {}",
                    operation, repo, prNumber, httpEx.getResponseBodyAsString());
            } else if (status == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("[GitHub] {} — Rate Limit (429) — repo:{} pr:{} | {}",
                    operation, repo, prNumber, httpEx.getResponseBodyAsString());
            } else {
                log.warn("[GitHub] {} 실패 — repo:{} pr:{} status:{} | {}",
                    operation, repo, prNumber, status, e.getMessage());
            }
        } else {
            log.warn("[GitHub] {} 실패 — repo:{} pr:{} | {}", operation, repo, prNumber, e.getMessage());
        }

        return CommentResult.failed(e.getMessage());
    }

    public record GithubComment(long id, String body, String user, String userType, String createdAt) {}

    public record CommentResult(boolean success, long commentId, String url, String error) {
        static CommentResult success(long commentId, String url) {
            return new CommentResult(true, commentId, url, null);
        }
        static CommentResult unavailable() {
            return new CommentResult(false, -1, null, "GitHub PAT not configured");
        }
        static CommentResult denied() {
            return new CommentResult(false, -1, null, "Repo not allowed");
        }
        static CommentResult failed(String error) {
            return new CommentResult(false, -1, null, error);
        }
    }
}
