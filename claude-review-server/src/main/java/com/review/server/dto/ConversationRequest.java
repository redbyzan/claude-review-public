package com.review.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;

public record ConversationRequest(
    @NotBlank String question,
    String diff,
    String repo,
    @JsonProperty("pr_number") Integer prNumber,
    @JsonProperty("pr_title") String prTitle,
    @JsonProperty("pr_author") String prAuthor,
    @JsonProperty("comment_type") String commentType,
    @JsonProperty("comment_id") Long commentId,
    @JsonProperty("review_comments") String reviewComments,
    @JsonProperty("conversation_history") String conversationHistory
) {
    public ConversationRequest {
        if (prTitle == null) prTitle = "(제목 없음)";
        if (prAuthor == null) prAuthor = "unknown";
        if (repo == null) repo = "unknown";
        if (prNumber == null) prNumber = 0;
        if (commentType == null) commentType = "issue_comment";
        if (commentId == null) commentId = 0L;
        if (reviewComments == null) reviewComments = "";
        if (conversationHistory == null) conversationHistory = "";
    }

    public boolean hasReviewComments() {
        return reviewComments != null && !reviewComments.isBlank()
            && !reviewComments.equals("[]");
    }

    public boolean hasConversationHistory() {
        return conversationHistory != null && !conversationHistory.isBlank()
            && !conversationHistory.equals("[]");
    }

    public boolean isDiffTooShort() {
        return diff == null || diff.trim().length() < 50;
    }
}
