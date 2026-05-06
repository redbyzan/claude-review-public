package com.review.server.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.review.server.dto.RebuttalRequest;
import com.review.server.dto.RebuttalResponse;
import com.review.server.service.RebuttalService;

@RestController
@RequestMapping
public class RebuttalController {

    private final RebuttalService rebuttalService;

    public RebuttalController(RebuttalService rebuttalService) {
        this.rebuttalService = rebuttalService;
    }

    @PostMapping("/review/rebuttal")
    public ResponseEntity<RebuttalResponse> rebuttal(@RequestBody RebuttalRequest request) {
        if (!rebuttalService.isEnabled()) {
            return ResponseEntity.ok(new RebuttalResponse(null, null, null, 0));
        }

        RebuttalResponse response = rebuttalService.generateRebuttal(request);
        return ResponseEntity.ok(response);
    }
}
