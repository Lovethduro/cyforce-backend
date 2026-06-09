package com.cyforce.controller;

import com.cyforce.model.User;
import com.cyforce.service.AdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "http://localhost:3000")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/dashboard/stats")
    public ResponseEntity<?> stats(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(adminService.adminOverview(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/users")
    public ResponseEntity<?> users(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(adminService.listUsers(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestHeader("X-User-Id") String userId, @RequestBody Map<String, String> body) {
        try {
            User created = adminService.createUser(userId, body);
            return ResponseEntity.ok(created);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<?> updateUser(@RequestHeader("X-User-Id") String userId, @PathVariable String id, @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(adminService.updateUser(userId, id, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            adminService.deleteUser(userId, id);
            return ResponseEntity.ok(Map.of("message", "User deactivated"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/tickets")
    public ResponseEntity<?> tickets(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(adminService.allTickets(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/leads")
    public ResponseEntity<?> leads(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(adminService.allLeads(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<?> auditLogs(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(adminService.auditLogs(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/announcements")
    public ResponseEntity<?> broadcastAnnouncement(@RequestHeader("X-User-Id") String userId,
                                                   @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(adminService.broadcastAnnouncement(userId, body.get("message"), body.get("audience")));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
