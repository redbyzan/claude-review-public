package com.review.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChangelogServiceTest {

    @Test
    void anchorCombinesDateAndSlug() {
        var entry = new ChangelogService.ChangelogEntry(
            "2026-04-16", "eyes-reaction", "멘션 즉시 인식",
            "desc", "", "", "", "feature", false);
        assertThat(entry.anchor()).isEqualTo("2026-04-16-eyes-reaction");
    }

    @Test
    void isNewTrueWithin7Days() {
        var today = java.time.LocalDate.now().toString();
        var entry = new ChangelogService.ChangelogEntry(
            today, "test", "t", "d", "", "", "", "feature", false);
        assertThat(entry.isNew()).isTrue();
    }

    @Test
    void isNewFalseOlderThan7Days() {
        var old = java.time.LocalDate.now().minusDays(10).toString();
        var entry = new ChangelogService.ChangelogEntry(
            old, "test", "t", "d", "", "", "", "feature", false);
        assertThat(entry.isNew()).isFalse();
    }
}
