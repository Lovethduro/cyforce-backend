package com.cyforce.controller;

import com.cyforce.service.LeaveService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/leave")
@CrossOrigin(origins = "http://localhost:3000")
public class LeaveController {

    private final LeaveService leaveService;

    public LeaveController(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    @GetMapping("/eligibility")
    public ResponseEntity<?> eligibility(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(leaveService.eligibility(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/requests")
    public ResponseEntity<?> myRequests(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(leaveService.myRequests(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/all")
    public ResponseEntity<?> all(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(leaveService.allRequests(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/pending")
    public ResponseEntity<?> pending(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(leaveService.pendingForReview(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/requests")
    public ResponseEntity<?> request(@RequestHeader("X-User-Id") String userId, @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(leaveService.requestLeave(userId, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/requests/{id}/approve")
    public ResponseEntity<?> approve(@RequestHeader("X-User-Id") String userId,
                                     @PathVariable String id,
                                     @RequestBody(required = false) Map<String, String> body) {
        try {
            return ResponseEntity.ok(leaveService.reviewLeave(userId, id, true, body != null ? body.get("note") : null));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/requests/{id}/reject")
    public ResponseEntity<?> reject(@RequestHeader("X-User-Id") String userId,
                                    @PathVariable String id,
                                    @RequestBody(required = false) Map<String, String> body) {
        try {
            return ResponseEntity.ok(leaveService.reviewLeave(userId, id, false, body != null ? body.get("note") : null));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/requests/{id}/cancel")
    public ResponseEntity<?> cancel(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            return ResponseEntity.ok(leaveService.cancelRequest(userId, id));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
