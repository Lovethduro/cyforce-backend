package com.cyforce.controller;

import com.cyforce.service.LeadService;
import com.cyforce.service.MessagingService;
import com.cyforce.service.SalesDashboardService;
import com.cyforce.service.SalesPlaybookService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
    private final SalesDashboardService salesDashboardService;
    private final SalesPlaybookService salesPlaybookService;

    public SalesController(LeadService leadService,
                           MessagingService messagingService,
                           SalesDashboardService salesDashboardService,
                           SalesPlaybookService salesPlaybookService) {
        this.leadService = leadService;
        this.messagingService = messagingService;
        this.salesDashboardService = salesDashboardService;
        this.salesPlaybookService = salesPlaybookService;
    }

    @GetMapping("/dashboard/overview")
    public ResponseEntity<?> overview(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(salesDashboardService.overview(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/dashboard/bonuses")
    public ResponseEntity<?> bonuses(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(salesDashboardService.bonusBreakdown(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/dashboard/deals-comparison")
    public ResponseEntity<?> dealsComparison(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(salesDashboardService.dealsComparison(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/dashboard/stats")
    public ResponseEntity<?> stats(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(leadService.salesStats(userId));
    }

    @GetMapping("/customers")
    public ResponseEntity<?> customers(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(salesDashboardService.listCustomers(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/customers")
    public ResponseEntity<?> createCustomer(@RequestHeader("X-User-Id") String userId,
                                            @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(salesDashboardService.createCustomer(userId, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/customers/report")
    public ResponseEntity<?> customersReport(@RequestHeader("X-User-Id") String userId,
                                             @RequestParam(defaultValue = "csv") String format) {
        try {
            byte[] report = salesDashboardService.customersReport(userId, format);
            String normalized = format == null ? "csv" : format.trim().toLowerCase();
            boolean pdf = "pdf".equals(normalized);
            String filename = "customers-" + java.time.LocalDate.now() + (pdf ? ".pdf" : ".csv");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(pdf ? MediaType.APPLICATION_PDF : MediaType.parseMediaType("text/csv"))
                    .body(report);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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

    @PostMapping("/leads/{id}/email")
    public ResponseEntity<?> sendLeadEmail(@RequestHeader("X-User-Id") String userId,
                                           @PathVariable String id,
                                           @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(leadService.sendLeadEmail(
                    userId, id, body.get("subject"), body.get("message")));
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

    @GetMapping("/conversations/queue")
    public ResponseEntity<?> conversationQueue(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(messagingService.conversationQueue(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/conversations/{id}/accept")
    public ResponseEntity<?> acceptConversation(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            return ResponseEntity.ok(messagingService.acceptConversation(userId, id));
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

    @PostMapping("/conversations/{id}/invoice")
    public ResponseEntity<?> sendInvoice(@RequestHeader("X-User-Id") String userId,
                                         @PathVariable String id,
                                         @RequestBody Map<String, Object> body) {
        try {
            long amount = body.get("amount") instanceof Number n
                    ? n.longValue()
                    : Long.parseLong(body.get("amount").toString());
            String description = body.get("description") != null ? body.get("description").toString() : "";
            return ResponseEntity.ok(messagingService.sendInvoice(userId, id, amount, description));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/conversations/{id}/forward")
    public ResponseEntity<?> forwardConversation(@RequestHeader("X-User-Id") String userId,
                                                 @PathVariable String id,
                                                 @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(messagingService.forwardToSupervisor(userId, id, body.get("reason")));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/playbook/categories")
    public ResponseEntity<?> playbookCategories() {
        return ResponseEntity.ok(salesPlaybookService.categories());
    }

    @GetMapping("/playbook")
    public ResponseEntity<?> playbookList(@RequestHeader("X-User-Id") String userId,
                                          @RequestParam(required = false) String category,
                                          @RequestParam(required = false) String q) {
        try {
            return ResponseEntity.ok(salesPlaybookService.listForAgent(userId, category, q));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/playbook/manage")
    public ResponseEntity<?> playbookManageList(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(salesPlaybookService.listForManage(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/playbook/{id}")
    public ResponseEntity<?> playbookDetail(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            return ResponseEntity.ok(salesPlaybookService.getForAgent(userId, id));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/playbook")
    public ResponseEntity<?> createPlaybookEntry(@RequestHeader("X-User-Id") String userId,
                                                @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(salesPlaybookService.create(userId, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/playbook/{id}")
    public ResponseEntity<?> updatePlaybookEntry(@RequestHeader("X-User-Id") String userId,
                                                 @PathVariable String id,
                                                 @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(salesPlaybookService.update(userId, id, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/playbook/{id}")
    public ResponseEntity<?> deletePlaybookEntry(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            salesPlaybookService.delete(userId, id);
            return ResponseEntity.ok(Map.of("message", "Deleted"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
