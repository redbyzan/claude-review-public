package com.review.server.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.review.server.dto.ConversationRequest;
import com.review.server.dto.ConversationResponse;
import com.review.server.service.ConversationService;

import jakarta.validation.Valid;

@RestController
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping("/review/conversation")
    public ResponseEntity<?> conversation(@Valid @RequestBody ConversationRequest request) {
        ConversationResponse response = conversationService.respond(request);
        return ResponseEntity.ok(response);
    }
}
