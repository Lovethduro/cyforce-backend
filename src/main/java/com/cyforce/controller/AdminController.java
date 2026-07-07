package com.cyforce.controller;

import com.cyforce.model.SystemSettings;
import com.cyforce.model.User;
import com.cyforce.service.AdminService;
import com.cyforce.service.DataManagementService;
import com.cyforce.service.KnowledgeBaseService;
import com.cyforce.service.SystemConfigService;
import com.cyforce.util.WebRequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "http://localhost:3000")
public class AdminController {

    private final AdminService adminService;
    private final SystemConfigService systemConfigService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DataManagementService dataManagementService;

    public AdminController(AdminService adminService,
                           SystemConfigService systemConfigService,
                           KnowledgeBaseService knowledgeBaseService,
                           DataManagementService dataManagementService) {
        this.adminService = adminService;
        this.systemConfigService = systemConfigService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.dataManagementService = dataManagementService;
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
    public ResponseEntity<?> createUser(@RequestHeader("X-User-Id") String userId,
                                        @RequestBody Map<String, String> body,
                                        HttpServletRequest request) {
        try {
            User created = adminService.createUser(userId, body, WebRequestUtils.clientIp(request));
            return ResponseEntity.ok(created);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<?> updateUser(@RequestHeader("X-User-Id") String userId,
                                        @PathVariable String id,
                                        @RequestBody Map<String, String> body,
                                        HttpServletRequest request) {
        try {
            return ResponseEntity.ok(adminService.updateUser(userId, id, body, WebRequestUtils.clientIp(request)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@RequestHeader("X-User-Id") String userId,
                                        @PathVariable String id,
                                        HttpServletRequest request) {
        try {
            adminService.deleteUser(userId, id, WebRequestUtils.clientIp(request));
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

    @GetMapping("/security-audit")
    public ResponseEntity<?> securityAudit(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(adminService.securityAuditLogs(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> sessions(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(adminService.listSessions(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/security-audit/report")
    public ResponseEntity<byte[]> securityAuditReport(@RequestHeader("X-User-Id") String userId,
                                                      @RequestParam(value = "format", defaultValue = "csv") String format,
                                                      HttpServletRequest request) {
        try {
            String normalized = format == null ? "csv" : format.trim().toLowerCase();
            byte[] report = adminService.securityAuditReport(userId, normalized, WebRequestUtils.clientIp(request));
            String filename = "security-audit-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "." + normalized;
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("pdf".equals(normalized) ? "application/pdf" : "text/csv"))
                    .body(report);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(null);
        }
    }

    @GetMapping("/audit-logs/report")
    public ResponseEntity<byte[]> auditLogsReport(@RequestHeader("X-User-Id") String userId,
                                                  @RequestParam(value = "format", defaultValue = "csv") String format,
                                                  HttpServletRequest request) {
        try {
            String normalized = format == null ? "csv" : format.trim().toLowerCase();
            byte[] report = adminService.auditLogsReport(userId, normalized, WebRequestUtils.clientIp(request));
            String filename = "audit-log-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "." + normalized;
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("pdf".equals(normalized) ? "application/pdf" : "text/csv"))
                    .body(report);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(null);
        }
    }

    @PostMapping("/announcements")
    public ResponseEntity<?> broadcastAnnouncement(@RequestHeader("X-User-Id") String userId,
                                                   @RequestBody Map<String, String> body,
                                                   HttpServletRequest request) {
        try {
            return ResponseEntity.ok(adminService.broadcastAnnouncement(
                    userId,
                    body.get("message"),
                    body.get("audience"),
                    WebRequestUtils.clientIp(request)
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/system-config")
    public ResponseEntity<?> systemConfig(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(systemConfigService.getAdminConfigView(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/system-config")
    public ResponseEntity<?> updateSystemConfig(@RequestHeader("X-User-Id") String userId,
                                                @RequestBody Map<String, Object> body) {
        try {
            SystemSettings updated = systemConfigService.updateAdminConfig(userId, body);
            return ResponseEntity.ok(systemConfigService.toAdminView(updated));
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

    @GetMapping("/data-management/overview")
    public ResponseEntity<?> dataManagementOverview(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(dataManagementService.overview(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/data-management/retention")
    public ResponseEntity<?> updateDataRetention(@RequestHeader("X-User-Id") String userId,
                                                 @RequestBody Map<String, Object> body) {
        try {
            int days = body.get("retentionDays") instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(String.valueOf(body.get("retentionDays")));
            return ResponseEntity.ok(dataManagementService.updateRetention(userId, days));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/data-management/backup")
    public ResponseEntity<?> runDataBackup(@RequestHeader("X-User-Id") String userId,
                                           HttpServletRequest request) {
        try {
            return ResponseEntity.ok(dataManagementService.runBackup(userId, WebRequestUtils.clientIp(request)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/data-management/export")
    public ResponseEntity<byte[]> exportData(@RequestHeader("X-User-Id") String userId,
                                           @RequestParam(value = "format", defaultValue = "csv") String format,
                                           HttpServletRequest request) {
        try {
            String normalized = format == null ? "csv" : format.trim().toLowerCase();
            byte[] report = dataManagementService.exportData(userId, normalized, WebRequestUtils.clientIp(request));
            String filename = "cyforce-data-export-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "." + normalized;
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("pdf".equals(normalized) ? "application/pdf" : "text/csv"))
                    .body(report);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(null);
        }
    }
}
