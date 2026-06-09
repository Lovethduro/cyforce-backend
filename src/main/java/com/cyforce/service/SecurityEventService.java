package com.cyforce.service;

import com.cyforce.model.User;
import com.cyforce.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class SecurityEventService {

    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    public SecurityEventService(AuditLogService auditLogService,
                                NotificationService notificationService,
                                UserRepository userRepository) {
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
    }

    public void recordLoginFailure(String email, String reason) {
        auditLogService.logSecurity(email, "LOGIN_FAILED", "Authentication", reason);
        notifyAdmins("Failed login attempt", email + " — " + reason, "warning");
    }

    public void recordRoleMismatch(String email, String requestedRole, String actualRole) {
        String details = "Selected " + requestedRole + " but account is " + actualRole;
        auditLogService.logSecurity(email, "AUTH_ROLE_MISMATCH", "Authentication", details);
        notifyAdmins("Role mismatch at login", email + " — " + details, "critical");
    }

    private void notifyAdmins(String title, String message, String type) {
        userRepository.findAll().stream()
                .filter(user -> "ADMIN".equalsIgnoreCase(user.getRole()))
                .forEach(admin -> notificationService.create(admin.getId(), title, message, type));
    }
}
