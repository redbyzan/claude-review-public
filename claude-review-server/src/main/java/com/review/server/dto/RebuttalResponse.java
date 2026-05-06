package com.review.server.dto;

public record RebuttalResponse(
    String rebuttal,
    Usage usage,
    String model,
    long elapsedMs
) {
    public record Usage(Long inputTokens, Long outputTokens) {}
}
