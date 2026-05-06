package com.review.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;

public record ReviewRequest(
    @NotBlank String diff,
    @JsonProperty("pr_title") String prTitle,
    @JsonProperty("pr_author") String prAuthor,
    String repo,
    @JsonProperty("pr_number") Integer prNumber,
    @JsonProperty("base_branch") String baseBranch,
    @JsonProperty("previous_reviews") String previousReviews
) {
    public ReviewRequest {
        if (prTitle == null) prTitle = "(제목 없음)";
        if (prAuthor == null) prAuthor = "unknown";
        if (repo == null) repo = "unknown";
        if (prNumber == null) prNumber = 0;
        if (baseBranch == null) baseBranch = "main";
        if (previousReviews == null) previousReviews = "";
    }

    public boolean isDiffTooShort() {
        return diff == null || diff.trim().length() < 50;
    }

    public boolean hasPreviousReviews() {
        return previousReviews != null && !previousReviews.isBlank()
            && !previousReviews.equals("[]");
    }
}
