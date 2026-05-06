package com.review.server.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReviewRequestTest {

    @Test
    void defaultsAreApplied() {
        var req = new ReviewRequest("a".repeat(100), null, null, null, null, null, null);
        assertThat(req.prTitle()).isEqualTo("(제목 없음)");
        assertThat(req.prAuthor()).isEqualTo("unknown");
        assertThat(req.repo()).isEqualTo("unknown");
        assertThat(req.prNumber()).isZero();
        assertThat(req.baseBranch()).isEqualTo("main");
        assertThat(req.previousReviews()).isEmpty();
    }

    @Test
    void diffTooShortWhenNull() {
        var req = new ReviewRequest(null, null, null, null, null, null, null);
        assertThat(req.isDiffTooShort()).isTrue();
    }

    @Test
    void diffTooShortWhenLessThan50() {
        var req = new ReviewRequest("short diff", null, null, null, null, null, null);
        assertThat(req.isDiffTooShort()).isTrue();
    }

    @Test
    void diffNotTooShortWhen50OrMore() {
        var req = new ReviewRequest("a".repeat(50), null, null, null, null, null, null);
        assertThat(req.isDiffTooShort()).isFalse();
    }

    @Test
    void hasPreviousReviewsTrue() {
        var req = new ReviewRequest("a".repeat(100), null, null, null, null, null, "[{\"review\": \"...\"}]");
        assertThat(req.hasPreviousReviews()).isTrue();
    }

    @Test
    void hasPreviousReviewsFalseWhenEmptyArray() {
        var req = new ReviewRequest("a".repeat(100), null, null, null, null, null, "[]");
        assertThat(req.hasPreviousReviews()).isFalse();
    }
}
