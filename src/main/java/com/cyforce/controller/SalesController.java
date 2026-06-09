package com.cyforce.controller;

import com.cyforce.service.LeadService;
import com.cyforce.service.MessagingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sales")
@CrossOrigin(origins = "http://localhost:3000")
public class SalesController {

    private final LeadService leadService;
    private final MessagingService messagingService;

    public SalesController(LeadService leadService, MessagingService messagingService) {
        this.leadService = leadService;
        this.messagingService = messagingService;
    }

    @GetMapping("/dashboard/stats")
    public ResponseEntity<?> stats(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(leadService.salesStats(userId));
    }

    @GetMapping("/leads")
    public ResponseEntity<?> leads(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(leadService.myLeads(userId));
    }

    @PostMapping("/leads")
    public ResponseEntity<?> createLead(@RequestHeader("X-User-Id") String userId, @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(leadService.createLead(userId, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/leads/{id}")
    public ResponseEntity<?> updateLead(@RequestHeader("X-User-Id") String userId, @PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(leadService.updateLead(userId, id, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/quotes")
    public ResponseEntity<?> quotes() {
        return ResponseEntity.ok(List.of());
    }

    @PostMapping("/quotes")
    public ResponseEntity<?> createQuote() {
        return ResponseEntity.ok(Map.of("message", "Quote module coming soon"));
    }

    @GetMapping("/conversations")
    public ResponseEntity<?> conversations(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(messagingService.salesConversations(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<?> conversationDetail(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            return ResponseEntity.ok(messagingService.conversationDetail(userId, id));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<?> sendMessage(@RequestHeader("X-User-Id") String userId,
                                         @PathVariable String id,
                                         @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(messagingService.sendMessage(userId, id, body.get("message"), null));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
