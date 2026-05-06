package com.review.server.dto;

public record RebuttalRequest(
    String reviewText,
    String diff,
    String repo,
    int prNumber
) {}
