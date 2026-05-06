package com.review.server.config;

import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import com.review.server.service.MonitorService;

@Component
public class ClaudeApiHealthIndicator implements HealthIndicator {

    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(30);
    private static final int CONSECUTIVE_FAIL_THRESHOLD = 3;

    private final MonitorService monitorService;

    public ClaudeApiHealthIndicator(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @Override
    public Health health() {
        Instant lastSuccess = monitorService.getLastReviewSuccess();
        int consecutiveFailures = monitorService.getConsecutiveFailures();

        boolean stale = lastSuccess != null &&
                Duration.between(lastSuccess, Instant.now()).compareTo(STALE_THRESHOLD) > 0;

        if (consecutiveFailures >= CONSECUTIVE_FAIL_THRESHOLD) {
            return Health.down()
                    .withDetail("consecutiveFailures", consecutiveFailures)
                    .withDetail("lastSuccess", lastSuccess)
                    .withDetail("reason", "consecutive failures >= " + CONSECUTIVE_FAIL_THRESHOLD)
                    .build();
        }

        if (stale) {
            return Health.up()
                    .withDetail("lastSuccess", lastSuccess)
                    .withDetail("warning", "no successful review in " + STALE_THRESHOLD.toMinutes() + " minutes")
                    .build();
        }

        return Health.up()
                .withDetail("lastSuccess", lastSuccess)
                .withDetail("consecutiveFailures", consecutiveFailures)
                .build();
    }
}
