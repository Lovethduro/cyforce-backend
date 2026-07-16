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
import com.cyforce.util.SensitiveDataMasker;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    private final AuditReportService auditReportService;
    private final UserSessionService userSessionService;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final List<String> SECURITY_ACTIONS = AuditLogService.SECURITY_ACTIONS;

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
                        EmailService emailService,
                        AuditReportService auditReportService,
                        UserSessionService userSessionService) {
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
        this.auditReportService = auditReportService;
        this.userSessionService = userSessionService;
    }

    public DashboardStatsResponse dashboardStats(String userId) {
        return userService.getDashboardStats(userId);
    }

    public List<UserListItemResponse> listUsers(String userId) {
        return userService.listUsers(userId);
    }

    public User createUser(String adminId, Map<String, String> body) {
        return createUser(adminId, body, null);
    }

    public User createUser(String adminId, Map<String, String> body, String clientIp) {
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
        auditLogService.log(admin, "USER_CREATE", "User Management", saved.getEmail(), clientIp);
        try {
            emailService.sendWelcomeCredentialsEmail(saved.getEmail(), saved.getFullName(), tempPassword);
        } catch (Exception e) {
            System.err.println("Failed to send welcome credentials email: " + e.getMessage());
        }
        return saved;
    }

    public User updateUser(String adminId, String targetId, Map<String, String> body) {
        return updateUser(adminId, targetId, body, null);
    }

    public User updateUser(String adminId, String targetId, Map<String, String> body, String clientIp) {
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
        auditLogService.log(admin, "USER_UPDATE", "User Management", saved.getEmail(), clientIp);
        return saved;
    }

    public void deleteUser(String adminId, String targetId) {
        deleteUser(adminId, targetId, null);
    }

    public void deleteUser(String adminId, String targetId, String clientIp) {
        User admin = requestUserService.requireUser(adminId);
        requestUserService.requireRole(admin, "ADMIN");
        if (admin.getId().equals(targetId)) {
            throw new RuntimeException("You cannot delete your own account");
        }
        User user = requestUserService.requireUser(targetId);
        user.setActive(false);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        auditLogService.log(admin, "USER_DEACTIVATE", "User Management", user.getEmail(), clientIp);
    }

    public List<Ticket> allTickets(String userId) {
        User viewer = requestUserService.requireUser(userId);
        requestUserService.requireRole(viewer, "ADMIN", "SUPERVISOR");
        List<Ticket> tickets = ticketRepository.findTop200ByOrderByCreatedAtDesc();
        if (!SensitiveDataMasker.shouldMaskForRole(viewer.getRole())) {
            return tickets;
        }
        return tickets.stream().map(this::maskTicketForAdmin).toList();
    }

    public List<Lead> allLeads(String userId) {
        User viewer = requestUserService.requireUser(userId);
        requestUserService.requireRole(viewer, "ADMIN", "SUPERVISOR");
        List<Lead> leads = leadRepository.findTop200ByOrderByCreatedAtDesc();
        if (!SensitiveDataMasker.shouldMaskForRole(viewer.getRole())) {
            return leads;
        }
        return leads.stream().map(this::maskLeadForAdmin).toList();
    }

    private Ticket maskTicketForAdmin(Ticket ticket) {
        Ticket copy = new Ticket();
        copy.setId(ticket.getId());
        copy.setCustomerId(ticket.getCustomerId());
        copy.setCustomerName(ticket.getCustomerName());
        copy.setCustomerEmail(SensitiveDataMasker.maskEmail(ticket.getCustomerEmail()));
        copy.setSubject(ticket.getSubject());
        copy.setDescription(SensitiveDataMasker.redactText(ticket.getDescription()));
        copy.setAttachmentUrl(ticket.getAttachmentUrl());
        copy.setCategory(ticket.getCategory());
        copy.setPriority(ticket.getPriority());
        copy.setStatus(ticket.getStatus());
        copy.setAssigneeId(ticket.getAssigneeId());
        copy.setAssigneeName(ticket.getAssigneeName());
        copy.setAssigneeAvatarUrl(ticket.getAssigneeAvatarUrl());
        copy.setSalesConversationId(ticket.getSalesConversationId());
        copy.setTransferredToSales(ticket.isTransferredToSales());
        copy.setTransferredAt(ticket.getTransferredAt());
        copy.setSlaEscalated(ticket.isSlaEscalated());
        copy.setSlaEscalatedAt(ticket.getSlaEscalatedAt());
        copy.setAdminTakeover(ticket.isAdminTakeover());
        copy.setAdminTakeoverAt(ticket.getAdminTakeoverAt());
        copy.setAdminTakeoverById(ticket.getAdminTakeoverById());
        copy.setGuestAccessToken(null);
        copy.setGuestTokenExpiresAt(ticket.getGuestTokenExpiresAt());
        copy.setMergedIntoTicketId(ticket.getMergedIntoTicketId());
        copy.setMergedAt(ticket.getMergedAt());
        copy.setCreatedAt(ticket.getCreatedAt());
        copy.setUpdatedAt(ticket.getUpdatedAt());
        return copy;
    }

    private Lead maskLeadForAdmin(Lead lead) {
        Lead copy = new Lead();
        copy.setId(lead.getId());
        copy.setName(lead.getName());
        copy.setEmail(SensitiveDataMasker.maskEmail(lead.getEmail()));
        copy.setPhone(SensitiveDataMasker.maskPhone(lead.getPhone()));
        copy.setCompany(lead.getCompany());
        copy.setSource(lead.getSource());
        copy.setStatus(lead.getStatus());
        copy.setScore(lead.getScore());
        copy.setOwnerId(lead.getOwnerId());
        copy.setOwnerName(lead.getOwnerName());
        copy.setConversationId(lead.getConversationId());
        copy.setQuoteType(lead.getQuoteType());
        copy.setDetails(SensitiveDataMasker.redactText(lead.getDetails()));
        copy.setProductId(lead.getProductId());
        copy.setProductName(lead.getProductName());
        copy.setQuantity(lead.getQuantity());
        copy.setDeliveryAddress(lead.getDeliveryAddress() != null ? "[address redacted]" : null);
        copy.setInstallationAddress(lead.getInstallationAddress() != null ? "[address redacted]" : null);
        copy.setPreferredInstallationDate(lead.getPreferredInstallationDate());
        copy.setSiteContactName(lead.getSiteContactName());
        copy.setSiteContactPhone(SensitiveDataMasker.maskPhone(lead.getSiteContactPhone()));
        copy.setProductType(lead.getProductType());
        copy.setExistingProductDetails(SensitiveDataMasker.redactText(lead.getExistingProductDetails()));
        copy.setCreatedAt(lead.getCreatedAt());
        copy.setUpdatedAt(lead.getUpdatedAt());
        return copy;
    }

    public List<AuditLog> auditLogs(String userId) {
        requestUserService.requireRole(requestUserService.requireUser(userId), "ADMIN", "SUPERVISOR");
        return auditLogService.all();
    }

    public List<AuditLog> securityAuditLogs(String userId) {
        requestUserService.requireRole(requestUserService.requireUser(userId), "ADMIN");
        return auditLogService.securityLogs();
    }

    public List<Map<String, Object>> listSessions(String userId) {
        return userSessionService.listSessionsForAdmin(userId);
    }

    public AdminDashboardOverviewResponse adminOverview(String userId) {
        requestUserService.requireRole(requestUserService.requireUser(userId), "ADMIN", "SUPERVISOR");
        DashboardStatsResponse stats = userService.buildDashboardStatsFast();
        List<AuditLog> logs = auditLogService.recent();

        long openTickets = ticketRepository.countByStatusIn(List.of("open", "in_progress"));
        long totalLeads = leadRepository.count();

        long activeSessions = userSessionService.countActiveSessions();

        List<AdminDashboardOverviewResponse.AnomalyAlertItem> anomalyAlerts = buildAnomalyAlerts(userId);

        var metrics = systemMetricsService.getDashboardMetricsBundle();

        List<UserListItemResponse> pendingUsers = userRepository
                .findTop10ByIsActiveTrueAndIsEmailVerifiedFalseOrderByCreatedAtDesc()
                .stream()
                .map(userService::toListItem)
                .toList();

        LocalDateTime weekAgo = LocalDate.now().minusDays(7).atStartOfDay();
        LocalDateTime twoWeeksAgo = LocalDate.now().minusDays(14).atStartOfDay();
        Map<String, User> activityUsers = new HashMap<>();
        userRepository.findByCreatedAtAfter(weekAgo).forEach(u -> activityUsers.put(u.getId(), u));
        userRepository.findByLastLoginAtAfter(weekAgo).forEach(u -> activityUsers.putIfAbsent(u.getId(), u));
        List<User> growthUsers = userRepository.findByCreatedAtAfter(twoWeeksAgo);

        return new AdminDashboardOverviewResponse(
                stats,
                openTickets,
                totalLeads,
                activeSessions,
                anomalyAlerts.size(),
                metrics.storageUsagePercent(),
                computeUserGrowthPercent(growthUsers),
                buildRegistrationActivity(new ArrayList<>(activityUsers.values())),
                pendingUsers,
                buildRecentActivity(logs),
                anomalyAlerts,
                metrics.storageBreakdown(),
                metrics.systemHealth(),
                logs.stream().limit(10).toList()
        );
    }

    public Map<String, Object> broadcastAnnouncement(String adminId, String message, String audience) {
        return broadcastAnnouncement(adminId, message, audience, null);
    }

    public Map<String, Object> broadcastAnnouncement(String adminId, String message, String audience, String clientIp) {
        User admin = requestUserService.requireUser(adminId);
        requestUserService.requireRole(admin, "ADMIN");
        if (message == null || message.isBlank()) {
            throw new RuntimeException("Announcement message is required");
        }
        String trimmed = message.trim();
        String target = audience == null || audience.isBlank() ? "all" : audience;
        int recipients = notificationService.broadcastToAudience("System Announcement", trimmed, target);
        auditLogService.log(admin, "ANNOUNCEMENT", "System", "Broadcast (" + target + ") to " + recipients + " users: " + trimmed, clientIp);
        return Map.of("message", "Announcement sent successfully", "recipients", recipients, "audience", target);
    }

    public byte[] securityAuditReport(String userId, String format, String clientIp) {
        User admin = requestUserService.requireUser(userId);
        requestUserService.requireRole(admin, "ADMIN");
        List<AuditLog> logs = auditLogService.securityLogs();
        String normalized = normalizeReportFormat(format);
        auditLogService.log(
                admin,
                "REPORT_GENERATED",
                "Security Audit",
                "Security audit " + normalized.toUpperCase() + " (" + logs.size() + " events)",
                clientIp
        );
        return buildReport(logs, "Security Audit Report", normalized);
    }

    public byte[] auditLogsReport(String userId, String format, String clientIp) {
        User admin = requestUserService.requireUser(userId);
        requestUserService.requireRole(admin, "ADMIN", "SUPERVISOR");
        List<AuditLog> logs = auditLogService.all();
        String normalized = normalizeReportFormat(format);
        auditLogService.log(
                admin,
                "REPORT_GENERATED",
                "Audit Logs",
                "Full audit log " + normalized.toUpperCase() + " (" + logs.size() + " events)",
                clientIp
        );
        return buildReport(logs, "Audit Log Report", normalized);
    }

    private byte[] buildReport(List<AuditLog> logs, String title, String format) {
        if ("pdf".equals(format)) {
            return auditReportService.toPdf(logs, title);
        }
        return auditReportService.toCsv(logs, title);
    }

    private String normalizeReportFormat(String format) {
        if (format == null || format.isBlank()) {
            return "csv";
        }
        String value = format.trim().toLowerCase(Locale.ROOT);
        if (!"csv".equals(value) && !"pdf".equals(value)) {
            throw new RuntimeException("Unsupported report format. Use csv or pdf.");
        }
        return value;
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

        notificationRepository.findTop30ByUserIdOrderByCreatedAtDesc(adminUserId).stream()
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
        if (action == null) {
            return "Info";
        }
        return switch (action) {
            case "AUTH_ROLE_MISMATCH" -> "Critical";
            case "LOGIN_FAILED" -> "Warning";
            case "PASSWORD_RESET_REQUESTED", "PASSWORD_RESET", "PASSWORD_CHANGED", "MFA_ENABLED", "MFA_DISABLED" -> "Info";
            case "LOGIN_SUCCESS" -> "Success";
            default -> "Info";
        };
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
