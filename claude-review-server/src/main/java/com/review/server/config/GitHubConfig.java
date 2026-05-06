package com.review.server.config;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class GitHubConfig {

    private static final Logger log = LoggerFactory.getLogger(GitHubConfig.class);

    private final String pat;
    private final String apiBaseUrl;
    private final String allowedRepos;

    public GitHubConfig(
            @Value("${github.pat:}") String pat,
            @Value("${github.api-base-url:https://api.github.com}") String apiBaseUrl,
            @Value("${github.allowed-repos:}") String allowedRepos) {
        this.pat = pat;
        this.apiBaseUrl = apiBaseUrl;
        this.allowedRepos = allowedRepos;
    }

    public String getPat() {
        return pat;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public String getAllowedRepos() {
        return allowedRepos;
    }

    public boolean isConfigured() {
        return pat != null && !pat.isBlank();
    }

    public List<String> getAllowedRepoList() {
        if (allowedRepos == null || allowedRepos.isBlank()) {
            return List.of();
        }
        return Arrays.stream(allowedRepos.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    @Bean
    public RestClient gitHubRestClient() {
        if (!isConfigured()) {
            log.warn("[GitHub] PAT 미설정 — GitHub API 기능 비활성화");
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(30_000);

        var builder = RestClient.builder()
            .baseUrl(apiBaseUrl)
            .defaultHeader("Accept", "application/vnd.github.v3+json")
            .defaultHeader("User-Agent", "claude-review-server/1.0")
            .requestFactory(factory);

        if (isConfigured()) {
            builder.defaultHeader("Authorization", "Bearer " + pat);
        }

        return builder.build();
    }
}
