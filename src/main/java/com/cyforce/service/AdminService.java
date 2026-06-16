package com.cyforce.service;

import com.cyforce.dto.AdminDashboardOverviewResponse;
import com.cyforce.dto.DashboardStatsResponse;
import com.cyforce.dto.UserListItemResponse;
import com.cyforce.model.AuditLog;
import com.cyforce.model.Lead;
import com.cyforce.model.Ticket;
import com.cyforce.model.User;
import com.cyforce.repository.AuditLogRepository;
import com.cyforce.repository.LeadRepository;
import com.cyforce.repository.NotificationRepository;
import com.cyforce.repository.TicketRepository;
import com.cyforce.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
@Service
public class AdminService {

    private final UserRepository userRepository;
    private final TicketRepository ticketRepository;
    private final LeadRepository leadRepository;
    private final RequestUserService requestUserService;
    private final PasswordService passwordService;
    private final AuditLogService auditLogService;
    private final UserService userService;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final SystemMetricsService systemMetricsService;
    private final AuditLogRepository auditLogRepository;
    private final EmailService emailService;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final List<String> SECURITY_ACTIONS = List.of(
            "LOGIN_FAILED", "AUTH_ROLE_MISMATCH", "DEACTIVATED_LOGIN"
    );

    public AdminService(UserRepository userRepository,
                        TicketRepository ticketRepository,
                        LeadRepository leadRepository,
                        RequestUserService requestUserService,
                        PasswordService passwordService,
                        AuditLogService auditLogService,
                        UserService userService,
                        NotificationRepository notificationRepository,
                        NotificationService notificationService,
                        SystemMetricsService systemMetricsService,
                        AuditLogRepository auditLogRepository,
                        EmailService emailService) {
        this.userRepository = userRepository;
        this.ticketRepository = ticketRepository;
        this.leadRepository = leadRepository;
        this.requestUserService = requestUserService;
        this.passwordService = passwordService;
        this.auditLogService = auditLogService;
        this.userService = userService;
        this.notificationRepository = notificationRepository;
        this.notificationService = notificationService;
        this.systemMetricsService = systemMetricsService;
        this.auditLogRepository = auditLogRepository;
        this.emailService = emailService;
    }

    public DashboardStatsResponse dashboardStats(String userId) {
        return userService.getDashboardStats(userId);
    }

    public List<UserListItemResponse> listUsers(String userId) {
        return userService.listUsers(userId);
    }

    public User createUser(String adminId, Map<String, String> body) {
        User admin = requestUserService.requireUser(adminId);
        requestUserService.requireRole(admin, "ADMIN");

        String email = body.get("email").trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new RuntimeException("Email already registered");
        }

        String fullName = body.get("fullName");
        if (fullName == null || fullName.isBlank()) {
            throw new RuntimeException("Full name is required");
        }

        String tempPassword = body.get("password");
        if (tempPassword == null || tempPassword.isBlank()) {
            tempPassword = passwordService.generateTemporaryPassword();
        }

        User user = new User();
        user.setFullName(fullName.trim());
        user.setEmail(email);
        user.setPhone(body.getOrDefault("phone", ""));
        user.setCompanyName(body.get("companyName"));
        user.setCustomerType(body.getOrDefault("customerType", "individual"));
        user.setRole(mapRole(body.get("role")));
        user.setAuthProvider("LOCAL");
        user.setPassword(passwordService.encode(tempPassword));
        user.setMustChangePassword(true);
        user.setEmailVerified(true);
        user.setEmailVerifiedAt(LocalDateTime.now());
        user.setActive(true);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        User saved = userRepository.save(user);
        auditLogService.log(admin, "USER_CREATE", "User Management", saved.getEmail());
        try {
            emailService.sendWelcomeCredentialsEmail(saved.getEmail(), saved.getFullName(), tempPassword);
        } catch (Exception e) {
            System.err.println("Failed to send welcome credentials email: " + e.getMessage());
        }
        return saved;
    }

    public User updateUser(String adminId, String targetId, Map<String, String> body) {
        User admin = requestUserService.requireUser(adminId);
        requestUserService.requireRole(admin, "ADMIN");
        User user = requestUserService.requireUser(targetId);
        if (body.get("role") != null) user.setRole(mapRole(body.get("role")));
        if (body.get("fullName") != null) user.setFullName(body.get("fullName"));
        if (body.get("phone") != null) user.setPhone(body.get("phone"));
        if (body.get("active") != null) user.setActive(Boolean.parseBoolean(body.get("active")));
        if (body.get("emailVerified") != null) {
            boolean verified = Boolean.parseBoolean(body.get("emailVerified"));
            user.setEmailVerified(verified);
            if (verified && user.getEmailVerifiedAt() == null) {
                user.setEmailVerifiedAt(LocalDateTime.now());
            }
        }
        if (body.get("password") != null && !body.get("password").isBlank()) {
            user.setPassword(passwordService.encode(body.get("password")));
            user.setMustChangePassword(true);
        }
        user.setUpdatedAt(LocalDateTime.now());
        User saved = userRepository.save(user);
        auditLogService.log(admin, "USER_UPDATE", "User Management", saved.getEmail());
        return saved;
    }

    public void deleteUser(String adminId, String targetId) {
        User admin = requestUserService.requireUser(adminId);
        requestUserService.requireRole(admin, "ADMIN");
        if (admin.getId().equals(targetId)) {
            throw new RuntimeException("You cannot delete your own account");
        }
        User user = requestUserService.requireUser(targetId);
        user.setActive(false);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        auditLogService.log(admin, "USER_DEACTIVATE", "User Management", user.getEmail());
    }

    public List<Ticket> allTickets(String userId) {
        requestUserService.requireRole(requestUserService.requireUser(userId), "ADMIN", "SUPERVISOR");
        return ticketRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<Lead> allLeads(String userId) {
        requestUserService.requireRole(requestUserService.requireUser(userId), "ADMIN", "SUPERVISOR");
        return leadRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<AuditLog> auditLogs(String userId) {
        requestUserService.requireRole(requestUserService.requireUser(userId), "ADMIN", "SUPERVISOR");
        return auditLogService.all();
    }

    public AdminDashboardOverviewResponse adminOverview(String userId) {
        DashboardStatsResponse stats = dashboardStats(userId);
        List<User> users = userRepository.findAll();
        List<Ticket> tickets = allTickets(userId);
        List<Lead> leads = allLeads(userId);
        List<AuditLog> logs = auditLogService.recent();

        long openTickets = tickets.stream()
                .filter(t -> "open".equals(t.getStatus()) || "in_progress".equals(t.getStatus()))
                .count();

        LocalDateTime sessionCutoff = LocalDateTime.now().minusHours(24);
        long activeSessions = users.stream()
                .filter(u -> u.getLastLoginAt() != null && u.getLastLoginAt().isAfter(sessionCutoff))
                .count();

        List<AdminDashboardOverviewResponse.AnomalyAlertItem> anomalyAlerts = buildAnomalyAlerts(userId);

        List<UserListItemResponse> pendingUsers = users.stream()
                .filter(u -> !u.isEmailVerified() || !u.isActive())
                .sorted(Comparator.comparing(User::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10)
                .map(userService::toListItem)
                .toList();

        return new AdminDashboardOverviewResponse(
                stats,
                openTickets,
                leads.size(),
                activeSessions,
                anomalyAlerts.size(),
                systemMetricsService.storageUsagePercent(),
                computeUserGrowthPercent(users),
                buildRegistrationActivity(users),
                pendingUsers,
                buildRecentActivity(logs),
                anomalyAlerts,
                systemMetricsService.storageBreakdown(),
                systemMetricsService.systemHealth(),
                logs.stream().limit(10).toList()
        );
    }

    public Map<String, Object> broadcastAnnouncement(String adminId, String message, String audience) {
        User admin = requestUserService.requireUser(adminId);
        requestUserService.requireRole(admin, "ADMIN");
        if (message == null || message.isBlank()) {
            throw new RuntimeException("Announcement message is required");
        }
        String trimmed = message.trim();
        String target = audience == null || audience.isBlank() ? "all" : audience;
        int recipients = notificationService.broadcastToAudience("System Announcement", trimmed, target);
        auditLogService.log(admin, "ANNOUNCEMENT", "System", "Broadcast (" + target + ") to " + recipients + " users: " + trimmed);
        return Map.of("message", "Announcement sent successfully", "recipients", recipients, "audience", target);
    }

    private List<AdminDashboardOverviewResponse.DayActivityItem> buildRegistrationActivity(List<User> users) {
        List<AdminDashboardOverviewResponse.DayActivityItem> items = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            String label = day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            long registrations = users.stream()
                    .filter(u -> u.getCreatedAt() != null && u.getCreatedAt().toLocalDate().equals(day))
                    .count();
            long logins = users.stream()
                    .filter(u -> u.getLastLoginAt() != null && u.getLastLoginAt().toLocalDate().equals(day))
                    .count();
            items.add(new AdminDashboardOverviewResponse.DayActivityItem(label, registrations, logins));
        }
        return items;
    }

    private List<AdminDashboardOverviewResponse.RecentActivityItem> buildRecentActivity(List<AuditLog> logs) {
        return logs.stream()
                .limit(10)
                .map(log -> new AdminDashboardOverviewResponse.RecentActivityItem(
                        log.getUserEmail(),
                        log.getAction(),
                        log.getModule(),
                        log.getCreatedAt() == null ? null : log.getCreatedAt().format(ISO)
                ))
                .toList();
    }

    private List<AdminDashboardOverviewResponse.AnomalyAlertItem> buildAnomalyAlerts(String adminUserId) {
        List<AdminDashboardOverviewResponse.AnomalyAlertItem> items = new ArrayList<>();

        notificationRepository.findByUserIdOrderByCreatedAtDesc(adminUserId).stream()
                .filter(n -> {
                    String type = n.getType() == null ? "" : n.getType().toLowerCase();
                    return type.equals("critical") || type.equals("warning") || type.equals("error");
                })
                .forEach(n -> items.add(new AdminDashboardOverviewResponse.AnomalyAlertItem(
                        "n-" + n.getId(),
                        capitalize(n.getType()),
                        n.getMessage() != null ? n.getMessage() : n.getTitle(),
                        n.getCreatedAt() == null ? null : n.getCreatedAt().format(ISO)
                )));

        auditLogRepository.findTop10ByActionInOrderByCreatedAtDesc(SECURITY_ACTIONS).forEach(log -> items.add(
                new AdminDashboardOverviewResponse.AnomalyAlertItem(
                        "a-" + log.getId(),
                        securityAlertType(log.getAction()),
                        log.getDetails() != null ? log.getDetails() : log.getAction(),
                        log.getCreatedAt() == null ? null : log.getCreatedAt().format(ISO)
                )
        ));

        return items.stream()
                .sorted(Comparator.comparing(
                        AdminDashboardOverviewResponse.AnomalyAlertItem::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .limit(10)
                .toList();
    }

    private String securityAlertType(String action) {
        if ("AUTH_ROLE_MISMATCH".equals(action)) {
            return "Critical";
        }
        if ("LOGIN_FAILED".equals(action)) {
            return "Warning";
        }
        return "Info";
    }

    private Double computeUserGrowthPercent(List<User> users) {
        LocalDate today = LocalDate.now();
        LocalDate weekAgo = today.minusDays(7);
        LocalDate twoWeeksAgo = today.minusDays(14);
        long thisWeek = users.stream()
                .filter(u -> u.getCreatedAt() != null
                        && !u.getCreatedAt().toLocalDate().isBefore(weekAgo)
                        && !u.getCreatedAt().toLocalDate().isAfter(today))
                .count();
        long lastWeek = users.stream()
                .filter(u -> u.getCreatedAt() != null
                        && !u.getCreatedAt().toLocalDate().isBefore(twoWeeksAgo)
                        && u.getCreatedAt().toLocalDate().isBefore(weekAgo))
                .count();
        if (lastWeek == 0) {
            return thisWeek > 0 ? 100.0 : 0.0;
        }
        return Math.round(((double) (thisWeek - lastWeek) / lastWeek) * 1000.0) / 10.0;
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) return "Info";
        return value.substring(0, 1).toUpperCase() + value.substring(1).toLowerCase();
    }

    private String mapRole(String role) {
        if (role == null) return "CUSTOMER";
        return switch (role.toLowerCase()) {
            case "admin" -> "ADMIN";
            case "supervisor" -> "SUPERVISOR";
            case "sales_agent", "sales" -> "SALES_AGENT";
            case "support_agent", "support" -> "SUPPORT_AGENT";
            default -> "CUSTOMER";
        };
    }
}
