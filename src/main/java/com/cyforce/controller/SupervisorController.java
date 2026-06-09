package com.cyforce.controller;

import com.cyforce.service.AdminService;
import com.cyforce.service.LeadService;
import com.cyforce.service.SupervisorDashboardService;
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

    public SupervisorController(AdminService adminService,
                                TicketService ticketService,
                                LeadService leadService,
                                SupervisorDashboardService supervisorDashboardService) {
        this.adminService = adminService;
        this.ticketService = ticketService;
        this.leadService = leadService;
        this.supervisorDashboardService = supervisorDashboardService;
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
            var overview = supervisorDashboardService.overview(userId, "all");
            return ResponseEntity.ok(overview.getTeamLeaderboard());
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/approvals/{id}/approve")
    public ResponseEntity<?> approve(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            return ResponseEntity.ok(adminService.updateUser(userId, id, Map.of("active", "true", "emailVerified", "true")));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/approvals/{id}/reject")
    public ResponseEntity<?> reject(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
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
