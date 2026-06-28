package com.cyforce.controller;

import com.cyforce.service.AdminService;
import com.cyforce.service.AnalyticsService;
import com.cyforce.service.LeadService;
import com.cyforce.service.SupervisorDashboardService;
import com.cyforce.service.SupervisorOpsService;
import com.cyforce.service.TicketService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/supervisor")
@CrossOrigin(origins = "http://localhost:3000")
public class SupervisorController {

    private final AdminService adminService;
    private final TicketService ticketService;
    private final LeadService leadService;
    private final SupervisorDashboardService supervisorDashboardService;
    private final SupervisorOpsService supervisorOpsService;
    private final AnalyticsService analyticsService;

    public SupervisorController(AdminService adminService,
                                TicketService ticketService,
                                LeadService leadService,
                                SupervisorDashboardService supervisorDashboardService,
                                SupervisorOpsService supervisorOpsService,
                                AnalyticsService analyticsService) {
        this.adminService = adminService;
        this.ticketService = ticketService;
        this.leadService = leadService;
        this.supervisorDashboardService = supervisorDashboardService;
        this.supervisorOpsService = supervisorOpsService;
        this.analyticsService = analyticsService;
    }

    @GetMapping("/dashboard/overview")
    public ResponseEntity<?> overview(@RequestHeader("X-User-Id") String userId,
                                      @RequestParam(required = false, defaultValue = "all") String team) {
        try {
            return ResponseEntity.ok(supervisorDashboardService.overview(userId, team));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/dashboard/stats")
    public ResponseEntity<?> stats(@RequestHeader("X-User-Id") String userId,
                                   @RequestParam(required = false, defaultValue = "all") String team) {
        try {
            return ResponseEntity.ok(supervisorDashboardService.overview(userId, team));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/tickets")
    public ResponseEntity<?> tickets(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(adminService.allTickets(userId));
    }

    @GetMapping("/leads")
    public ResponseEntity<?> leads(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(adminService.allLeads(userId));
    }

    @GetMapping("/agents/performance")
    public ResponseEntity<?> performance(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(analyticsService.agentPerformance(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/sales-agents")
    public ResponseEntity<?> salesAgents(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(supervisorOpsService.salesAgentsWithLoad(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/leads")
    public ResponseEntity<?> createLead(@RequestHeader("X-User-Id") String userId,
                                        @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(supervisorOpsService.createLead(userId, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/leads/{leadId}/assign/preview")
    public ResponseEntity<?> previewLeadAssignment(@RequestHeader("X-User-Id") String userId,
                                                   @PathVariable String leadId) {
        try {
            return ResponseEntity.ok(supervisorOpsService.previewLeadAssignment(userId, leadId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/leads/{leadId}/assign")
    public ResponseEntity<?> assignLead(@RequestHeader("X-User-Id") String userId,
                                        @PathVariable String leadId,
                                        @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(supervisorOpsService.assignLead(userId, leadId, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/approvals")
    public ResponseEntity<?> approvals(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(supervisorOpsService.pendingApprovals(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/approvals/{id}/review")
    public ResponseEntity<?> reviewApproval(@RequestHeader("X-User-Id") String userId,
                                            @PathVariable String id,
                                            @RequestBody Map<String, Object> body) {
        try {
            boolean approve = Boolean.TRUE.equals(body.get("approve"));
            String note = body.get("note") != null ? body.get("note").toString() : null;
            return ResponseEntity.ok(supervisorOpsService.reviewApproval(userId, id, approve, note));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/announcements")
    public ResponseEntity<?> broadcast(@RequestHeader("X-User-Id") String userId,
                                       @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(supervisorOpsService.broadcast(
                    userId,
                    body.get("message"),
                    body.getOrDefault("audience", "all")
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/approvals/{id}/approve")
    public ResponseEntity<?> approve(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            var approval = supervisorOpsService.pendingApprovals(userId).stream()
                    .filter(a -> id.equals(a.get("id")) || id.equals(a.get("approvalId")))
                    .findFirst();
            if (approval.isPresent()) {
                return ResponseEntity.ok(supervisorOpsService.reviewApproval(userId, id, true, null));
            }
            return ResponseEntity.ok(adminService.updateUser(userId, id, Map.of("active", "true", "emailVerified", "true")));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/approvals/{id}/reject")
    public ResponseEntity<?> reject(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            var approval = supervisorOpsService.pendingApprovals(userId).stream()
                    .filter(a -> id.equals(a.get("id")) || id.equals(a.get("approvalId")))
                    .findFirst();
            if (approval.isPresent()) {
                return ResponseEntity.ok(supervisorOpsService.reviewApproval(userId, id, false, null));
            }
            adminService.deleteUser(userId, id);
            return ResponseEntity.ok(Map.of("message", "Rejected"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/reports/tickets")
    public ResponseEntity<?> ticketReport(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(ticketService.allOpenTickets(userId));
    }

    @GetMapping("/reports/sales")
    public ResponseEntity<?> salesReport(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(leadService.allLeads(userId));
    }
}
