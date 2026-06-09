package com.cyforce.service;

import com.cyforce.model.AuditLog;
import com.cyforce.model.User;
import com.cyforce.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void log(User user, String action, String module, String details) {
        AuditLog entry = new AuditLog();
        entry.setUserId(user.getId());
        entry.setUserEmail(user.getEmail());
        entry.setAction(action);
        entry.setModule(module);
        entry.setDetails(details);
        entry.setCreatedAt(LocalDateTime.now());
        auditLogRepository.save(entry);
    }

    public void logSecurity(String email, String action, String module, String details) {
        AuditLog entry = new AuditLog();
        entry.setUserEmail(email == null ? "unknown" : email);
        entry.setAction(action);
        entry.setModule(module);
        entry.setDetails(details);
        entry.setCreatedAt(LocalDateTime.now());
        auditLogRepository.save(entry);
    }

    public List<AuditLog> recent() {
        return auditLogRepository.findTop20ByOrderByCreatedAtDesc();
    }

    public List<AuditLog> all() {
        return auditLogRepository.findAllByOrderByCreatedAtDesc();
    }
}
