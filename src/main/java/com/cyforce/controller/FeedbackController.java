package com.cyforce.controller;

import com.cyforce.service.RatingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@CrossOrigin(origins = "http://localhost:3000")
public class FeedbackController {

    private final RatingService ratingService;

    public FeedbackController(RatingService ratingService) {
        this.ratingService = ratingService;
    }

    @GetMapping("/api/feedback/purchase-survey/{token}")
    public ResponseEntity<?> getPurchaseSurvey(@PathVariable String token) {
        try {
            return ResponseEntity.ok(ratingService.getPurchaseSurvey(token));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/feedback/purchase-survey/{token}")
    public ResponseEntity<?> submitPurchaseSurvey(@PathVariable String token, @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(ratingService.submitPurchaseSurvey(token, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/admin/feedback")
    public ResponseEntity<?> adminFeedback(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(ratingService.listFeedbackForStaff(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/supervisor/feedback")
    public ResponseEntity<?> supervisorFeedback(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(ratingService.listFeedbackForStaff(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/feedback/overview")
    public ResponseEntity<?> feedbackOverview(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(ratingService.feedbackOverview(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
