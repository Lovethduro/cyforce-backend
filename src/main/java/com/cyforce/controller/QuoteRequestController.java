package com.cyforce.controller;

import com.cyforce.service.LeadService;
import com.cyforce.service.MessagingService;
import com.cyforce.service.QuoteBundleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/quotes")
@CrossOrigin(origins = "http://localhost:3000")
public class QuoteRequestController {

    private final LeadService leadService;
    private final MessagingService messagingService;
    private final QuoteBundleService quoteBundleService;

    public QuoteRequestController(LeadService leadService,
                                  MessagingService messagingService,
                                  QuoteBundleService quoteBundleService) {
        this.leadService = leadService;
        this.messagingService = messagingService;
        this.quoteBundleService = quoteBundleService;
    }

    @PostMapping("/suggest-bundle")
    public ResponseEntity<?> suggestBundle(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(quoteBundleService.suggestBundle(body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/request")
    public ResponseEntity<?> requestQuote(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(leadService.createPublicQuoteRequest(body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/portal/{token}")
    public ResponseEntity<?> getPortal(@PathVariable String token) {
        try {
            return ResponseEntity.ok(messagingService.guestConversationDetail(token));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/portal/{token}/messages")
    public ResponseEntity<?> guestMessage(@PathVariable String token, @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(messagingService.guestSendMessage(token, body.get("message")));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
