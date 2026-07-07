package com.cyforce.controller;

import com.cyforce.service.SystemConfigService;
import com.cyforce.service.SupportResponseEstimateService;
import com.cyforce.service.TicketService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/public/support")
@CrossOrigin(origins = "http://localhost:3000")
public class PublicSupportController {

    private final SystemConfigService systemConfigService;
    private final TicketService ticketService;
    private final SupportResponseEstimateService responseEstimateService;

    public PublicSupportController(SystemConfigService systemConfigService,
                                   TicketService ticketService,
                                   SupportResponseEstimateService responseEstimateService) {
        this.systemConfigService = systemConfigService;
        this.ticketService = ticketService;
        this.responseEstimateService = responseEstimateService;
    }

    @GetMapping("/config")
    public ResponseEntity<?> config() {
        return ResponseEntity.ok(systemConfigService.publicSupportConfig());
    }

    @GetMapping("/estimated-response")
    public ResponseEntity<?> estimatedResponse(@RequestParam(defaultValue = "medium") String priority) {
        return ResponseEntity.ok(responseEstimateService.estimate(priority));
    }

    @PostMapping("/tickets")
    public ResponseEntity<?> createGuestTicket(@RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(ticketService.createGuestTicket(body, null));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/tickets/with-attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> createGuestTicketWithAttachment(@RequestParam String name,
                                                             @RequestParam String email,
                                                             @RequestParam String subject,
                                                             @RequestParam String description,
                                                             @RequestParam String category,
                                                             @RequestParam(defaultValue = "medium") String priority,
                                                             @RequestParam(required = false) MultipartFile attachment) {
        try {
            Map<String, String> body = new HashMap<>();
            body.put("name", name);
            body.put("email", email);
            body.put("subject", subject);
            body.put("description", description);
            body.put("category", category);
            body.put("priority", priority);
            return ResponseEntity.ok(ticketService.createGuestTicket(body, attachment));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/tickets/portal/{token}")
    public ResponseEntity<?> guestTicketPortal(@PathVariable String token) {
        try {
            return ResponseEntity.ok(ticketService.guestTicketDetail(token));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/tickets/portal/{token}/reply")
    public ResponseEntity<?> guestTicketReply(@PathVariable String token, @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(ticketService.guestTicketReply(token, body.get("message")));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
