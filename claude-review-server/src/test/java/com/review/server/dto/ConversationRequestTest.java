package com.review.server.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConversationRequestTest {

    @Test
    void defaultsAreApplied() {
        var req = new ConversationRequest("question", null, null, null, null, null, null, null, null, null);
        assertThat(req.prTitle()).isEqualTo("(제목 없음)");
        assertThat(req.prAuthor()).isEqualTo("unknown");
        assertThat(req.repo()).isEqualTo("unknown");
        assertThat(req.prNumber()).isZero();
        assertThat(req.commentType()).isEqualTo("issue_comment");
        assertThat(req.commentId()).isZero();
        assertThat(req.reviewComments()).isEmpty();
        assertThat(req.conversationHistory()).isEmpty();
    }

    @Test
    void diffTooShortWhenNull() {
        var req = new ConversationRequest("q", null, null, null, null, null, null, null, null, null);
        assertThat(req.isDiffTooShort()).isTrue();
    }

    @Test
    void diffNotTooShortWhen50OrMore() {
        var req = new ConversationRequest("q", "a".repeat(50), null, null, null, null, null, null, null, null);
        assertThat(req.isDiffTooShort()).isFalse();
    }

    @Test
    void hasReviewCommentsTrue() {
        var req = new ConversationRequest("q", null, null, null, null, null, null, null, "[\"review\"]", null);
        assertThat(req.hasReviewComments()).isTrue();
    }

    @Test
    void hasReviewCommentsFalseWhenEmpty() {
        var req = new ConversationRequest("q", null, null, null, null, null, null, null, "", null);
        assertThat(req.hasReviewComments()).isFalse();
    }

    @Test
    void hasConversationHistoryTrue() {
        var req = new ConversationRequest("q", null, null, null, null, null, null, null, null, "[\"hist\"]");
        assertThat(req.hasConversationHistory()).isTrue();
    }

    @Test
    void hasConversationHistoryFalseWhenEmptyArray() {
        var req = new ConversationRequest("q", null, null, null, null, null, null, null, null, "[]");
        assertThat(req.hasConversationHistory()).isFalse();
    }
}
