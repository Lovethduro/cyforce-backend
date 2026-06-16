package com.cyforce.controller;

import com.cyforce.model.User;
import com.cyforce.service.AdminService;
import com.cyforce.service.KnowledgeBaseService;
import com.cyforce.service.SystemConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "http://localhost:3000")
public class AdminController {

    private final AdminService adminService;
    private final SystemConfigService systemConfigService;
    private final KnowledgeBaseService knowledgeBaseService;

    public AdminController(AdminService adminService,
                           SystemConfigService systemConfigService,
                           KnowledgeBaseService knowledgeBaseService) {
        this.adminService = adminService;
        this.systemConfigService = systemConfigService;
        this.knowledgeBaseService = knowledgeBaseService;
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

    @GetMapping("/system-config")
    public ResponseEntity<?> systemConfig(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(systemConfigService.getAdminConfig(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/system-config")
    public ResponseEntity<?> updateSystemConfig(@RequestHeader("X-User-Id") String userId,
                                                @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(systemConfigService.updateAdminConfig(userId, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/knowledge-base")
    public ResponseEntity<?> knowledgeBase(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(knowledgeBaseService.listForAdmin(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/knowledge-base")
    public ResponseEntity<?> createKnowledgeArticle(@RequestHeader("X-User-Id") String userId,
                                                    @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(knowledgeBaseService.createArticle(userId, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/knowledge-base/{id}")
    public ResponseEntity<?> updateKnowledgeArticle(@RequestHeader("X-User-Id") String userId,
                                                    @PathVariable String id,
                                                    @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(knowledgeBaseService.updateArticle(userId, id, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/knowledge-base/{id}")
    public ResponseEntity<?> deleteKnowledgeArticle(@RequestHeader("X-User-Id") String userId,
                                                    @PathVariable String id) {
        try {
            knowledgeBaseService.deleteArticle(userId, id);
            return ResponseEntity.ok(Map.of("message", "Deleted"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
