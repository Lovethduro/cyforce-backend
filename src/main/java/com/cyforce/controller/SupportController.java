package com.cyforce.controller;

import com.cyforce.service.SupportDashboardService;
import com.cyforce.service.TicketCopilotService;
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
    private final TicketCopilotService ticketCopilotService;

    public SupportController(TicketService ticketService,
                             SupportDashboardService supportDashboardService,
                             TicketCopilotService ticketCopilotService) {
        this.ticketService = ticketService;
        this.supportDashboardService = supportDashboardService;
        this.ticketCopilotService = ticketCopilotService;
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
        return ResponseEntity.ok(ticketService.supportStats(userId));
    }

    @GetMapping("/tickets")
    public ResponseEntity<?> myTickets(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(ticketService.supportTickets(userId));
    }

    @GetMapping("/macros")
    public ResponseEntity<?> macros(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(ticketService.supportMacros(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/tickets/all")
    public ResponseEntity<?> allOpen(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(ticketService.allOpenTickets(userId));
    }

    @GetMapping("/tickets/{id}")
    public ResponseEntity<?> ticketDetail(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            return ResponseEntity.ok(Map.of(
                    "ticket", ticketService.getTicket(userId, id),
                    "messages", ticketService.getMessages(userId, id)
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/tickets/{id}/timeline")
    public ResponseEntity<?> ticketTimeline(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            return ResponseEntity.ok(ticketService.ticketTimeline(userId, id));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Request failed"));
        }
    }

    @GetMapping("/tickets/{id}/duplicates")
    public ResponseEntity<?> duplicates(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            return ResponseEntity.ok(ticketService.findDuplicateTickets(userId, id));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/tickets/{id}/merge")
    public ResponseEntity<?> merge(@RequestHeader("X-User-Id") String userId,
                                   @PathVariable String id,
                                   @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(ticketService.mergeTickets(userId, id, body.get("duplicateTicketId")));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/tickets/{id}/copilot/summarize")
    public ResponseEntity<?> copilotSummarize(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            return ResponseEntity.ok(ticketCopilotService.summarize(userId, id));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/tickets/{id}/copilot/suggest-reply")
    public ResponseEntity<?> copilotSuggestReply(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            return ResponseEntity.ok(ticketCopilotService.suggestReply(userId, id));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/tickets/{id}/copilot/analyze")
    public ResponseEntity<?> copilotAnalyze(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            return ResponseEntity.ok(ticketCopilotService.analyze(userId, id));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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

    @PostMapping("/tickets/{id}/takeover")
    public ResponseEntity<?> takeover(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            return ResponseEntity.ok(ticketService.adminTakeover(userId, id));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/tickets/{id}/transfer-to-sales")
    public ResponseEntity<?> transferToSales(@RequestHeader("X-User-Id") String userId,
                                             @PathVariable String id,
                                             @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(ticketService.transferToSales(userId, id, body.get("note")));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/agents")
    public ResponseEntity<?> agents(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(ticketService.listSupportAgents(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/tickets/{id}/transfer-to-agent")
    public ResponseEntity<?> transferToAgent(@RequestHeader("X-User-Id") String userId,
                                             @PathVariable String id,
                                             @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(ticketService.transferToAgent(
                    userId, id, body.get("agentId"), body.get("note")));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
