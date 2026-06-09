package com.cyforce.controller;

import com.cyforce.service.SupportDashboardService;
import com.cyforce.service.TicketService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/support")
@CrossOrigin(origins = "http://localhost:3000")
public class SupportController {

    private final TicketService ticketService;
    private final SupportDashboardService supportDashboardService;

    public SupportController(TicketService ticketService, SupportDashboardService supportDashboardService) {
        this.ticketService = ticketService;
        this.supportDashboardService = supportDashboardService;
    }

    @GetMapping("/dashboard/overview")
    public ResponseEntity<?> overview(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(supportDashboardService.overview(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/agent/status")
    public ResponseEntity<?> updateStatus(@RequestHeader("X-User-Id") String userId, @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(supportDashboardService.updateStatus(userId, body.get("status")));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/dashboard/stats")
    public ResponseEntity<?> stats(@RequestHeader("X-User-Id") String userId) {
        try {
            var overview = supportDashboardService.overview(userId);
            return ResponseEntity.ok(Map.of(
                    "assignedTickets", overview.getStats().getOpenTickets(),
                    "resolvedTickets", overview.getStats().getResolvedToday(),
                    "avgResponseTime", overview.getStats().getAvgResponseTime(),
                    "slaCompliance", overview.getStats().getSlaCompliance(),
                    "satisfactionRating", overview.getStats().getSatisfactionRating()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ticketService.supportStats(userId));
        }
    }

    @GetMapping("/tickets")
    public ResponseEntity<?> myTickets(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(ticketService.supportTickets(userId));
    }

    @GetMapping("/tickets/all")
    public ResponseEntity<?> allOpen(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(ticketService.allOpenTickets(userId));
    }

    @PutMapping("/tickets/{id}/assign")
    public ResponseEntity<?> assign(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            return ResponseEntity.ok(ticketService.assignToMe(userId, id));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/tickets/{id}/status")
    public ResponseEntity<?> status(@RequestHeader("X-User-Id") String userId, @PathVariable String id, @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(ticketService.updateStatus(userId, id, body.get("status")));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/tickets/{id}/response")
    public ResponseEntity<?> response(@RequestHeader("X-User-Id") String userId, @PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            String message = (String) body.get("message");
            boolean internalNote = Boolean.TRUE.equals(body.get("internalNote"));
            return ResponseEntity.ok(ticketService.addResponse(userId, id, message, internalNote));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
