package com.cyforce.service;

import com.cyforce.model.AuditLog;
import com.cyforce.model.User;
import com.cyforce.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuditLogService {

    public static final List<String> SECURITY_ACTIONS = List.of(
            "LOGIN_FAILED",
            "LOGIN_SUCCESS",
            "LOGOUT",
            "AUTH_ROLE_MISMATCH",
            "PASSWORD_RESET_REQUESTED",
            "PASSWORD_RESET",
            "PASSWORD_CHANGED",
            "MFA_ENABLED",
            "MFA_DISABLED"
    );

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void log(User user, String action, String module, String details) {
        log(user, action, module, details, null);
    }

    public void log(User user, String action, String module, String details, String clientIp) {
        AuditLog entry = new AuditLog();
        entry.setUserId(user.getId());
        entry.setUserEmail(user.getEmail());
        entry.setAction(action);
        entry.setModule(module);
        entry.setDetails(details);
        entry.setClientIp(clientIp);
        entry.setCreatedAt(LocalDateTime.now());
        auditLogRepository.save(entry);
    }

    public void logSecurity(String email, String action, String module, String details) {
        logSecurity(email, action, module, details, null, null);
    }

    public void logSecurity(String email, String action, String module, String details, String userId, String clientIp) {
        AuditLog entry = new AuditLog();
        entry.setUserId(userId);
        entry.setUserEmail(email == null ? "unknown" : email);
        entry.setAction(action);
        entry.setModule(module);
        entry.setDetails(details);
        entry.setClientIp(clientIp);
        entry.setCreatedAt(LocalDateTime.now());
        auditLogRepository.save(entry);
    }

    public List<AuditLog> securityLogs() {
        return auditLogRepository.findByActionInOrderByCreatedAtDesc(AuditLogService.SECURITY_ACTIONS);
    }

    public List<AuditLog> recent() {
        return auditLogRepository.findTop20ByOrderByCreatedAtDesc();
    }

    public List<AuditLog> all() {
        return auditLogRepository.findAllByOrderByCreatedAtDesc();
    }
}
