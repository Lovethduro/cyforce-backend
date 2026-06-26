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
        recordLoginFailure(email, reason, null);
    }

    public void recordLoginFailure(String email, String reason, String clientIp) {
        auditLogService.logSecurity(email, "LOGIN_FAILED", "Authentication", reason, null, clientIp);
        notifyAdmins("Failed login attempt", email + " — " + reason, "warning");
    }

    public void recordLoginSuccess(User user, String clientIp) {
        String details = "Signed in as " + (user.getRole() == null ? "user" : user.getRole());
        auditLogService.logSecurity(user.getEmail(), "LOGIN_SUCCESS", "Authentication", details, user.getId(), clientIp);
    }

    public void recordOAuthLoginSuccess(User user, String provider, String clientIp) {
        String details = "Signed in via " + provider + " as " + (user.getRole() == null ? "user" : user.getRole());
        auditLogService.logSecurity(user.getEmail(), "LOGIN_SUCCESS", "Authentication", details, user.getId(), clientIp);
    }

    public void recordRoleMismatch(String email, String requestedRole, String actualRole) {
        recordRoleMismatch(email, requestedRole, actualRole, null);
    }

    public void recordRoleMismatch(String email, String requestedRole, String actualRole, String clientIp) {
        String details = "Selected " + requestedRole + " but account is " + actualRole;
        auditLogService.logSecurity(email, "AUTH_ROLE_MISMATCH", "Authentication", details, null, clientIp);
        notifyAdmins("Role mismatch at login", email + " — " + details, "critical");
    }

    public void recordPasswordResetRequested(String email, String clientIp) {
        auditLogService.logSecurity(email, "PASSWORD_RESET_REQUESTED", "Authentication", "Password reset link requested", null, clientIp);
    }

    public void recordPasswordReset(String email, String clientIp) {
        auditLogService.logSecurity(email, "PASSWORD_RESET", "Authentication", "Password reset completed", null, clientIp);
    }

    public void recordPasswordChange(User user, String clientIp) {
        auditLogService.logSecurity(user.getEmail(), "PASSWORD_CHANGED", "Authentication", "Password updated", user.getId(), clientIp);
    }

    public void recordMfaEnabled(User user, String method) {
        recordMfaEnabled(user, method, null);
    }

    public void recordMfaEnabled(User user, String method, String clientIp) {
        auditLogService.logSecurity(
                user.getEmail(),
                "MFA_ENABLED",
                "Authentication",
                "MFA enabled (" + (method == null ? "unknown" : method) + ")",
                user.getId(),
                clientIp
        );
    }

    public void recordMfaDisabled(User user) {
        recordMfaDisabled(user, null);
    }

    public void recordMfaDisabled(User user, String clientIp) {
        auditLogService.logSecurity(
                user.getEmail(),
                "MFA_DISABLED",
                "Authentication",
                "MFA disabled",
                user.getId(),
                clientIp
        );
    }

    private void notifyAdmins(String title, String message, String type) {
        userRepository.findAll().stream()
                .filter(user -> "ADMIN".equalsIgnoreCase(user.getRole()))
                .forEach(admin -> notificationService.create(admin.getId(), title, message, type));
    }
}
