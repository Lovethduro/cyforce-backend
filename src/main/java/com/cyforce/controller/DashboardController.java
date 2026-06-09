package com.cyforce.controller;

import com.cyforce.dto.DashboardStatsResponse;
import com.cyforce.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(origins = "http://localhost:3000")
public class DashboardController {

    private final UserService userService;

    public DashboardController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getStats(@RequestHeader("X-User-Id") String userId) {
        try {
            DashboardStatsResponse stats = userService.getDashboardStats(userId);
            return ResponseEntity.ok(stats);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
    }
}
