package com.review.server.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.review.server.dto.ReviewRequest;
import com.review.server.dto.ReviewResponse;
import com.review.server.service.ReviewService;

import jakarta.validation.Valid;

@RestController
public class ReviewController {

    private static final Logger log = LoggerFactory.getLogger(ReviewController.class);

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping({"/review", "/review/"})
    public ResponseEntity<?> review(@Valid @RequestBody ReviewRequest request) {
        log.info("[Controller] /review ENTER — repo:{} pr:{} diffLength:{}",
                request.repo(), request.prNumber(), request.diff() != null ? request.diff().length() : 0);
        if (request.isDiffTooShort()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "diff가 없거나 너무 짧습니다."));
        }

        ReviewResponse response = reviewService.review(request);
        return ResponseEntity.ok(response);
    }
}
