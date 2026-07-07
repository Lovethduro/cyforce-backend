package com.cyforce.controller;

import com.cyforce.service.AnalyticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
@CrossOrigin(origins = "http://localhost:3000")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/overview")
    public ResponseEntity<?> overview(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(analyticsService.overview(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/performance")
    public ResponseEntity<?> performance(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(analyticsService.agentPerformance(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
