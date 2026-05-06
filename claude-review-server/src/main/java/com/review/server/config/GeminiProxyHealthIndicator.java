package com.review.server.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GeminiProxyHealthIndicator implements HealthIndicator {

    private final boolean enabled;

    public GeminiProxyHealthIndicator(@Value("${cli-proxy.url:}") String proxyUrl) {
        this.enabled = proxyUrl != null && !proxyUrl.isBlank();
    }

    @Override
    public Health health() {
        if (!enabled) {
            return Health.up()
                    .withDetail("status", "disabled")
                    .withDetail("reason", "cli-proxy.url not configured")
                    .build();
        }

        return Health.up()
                .withDetail("status", "enabled")
                .build();
    }
}
