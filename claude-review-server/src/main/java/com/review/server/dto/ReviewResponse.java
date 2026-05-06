package com.review.server.dto;

public record ReviewResponse(
    String review,
    Usage usage,
    String model,
    long elapsedMs
) {
    public record Usage(Long inputTokens, Long outputTokens) {}
}
