package com.review.server.dto;

public record ConversationResponse(
    String reply,
    Usage usage,
    String model,
    long elapsedMs
) {
    public record Usage(Long inputTokens, Long outputTokens) {}
}
