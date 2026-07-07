package com.cyforce.service;

import com.cyforce.model.Lead;
import com.cyforce.model.SystemSettings;
import com.cyforce.model.Ticket;
import com.cyforce.model.User;
import com.cyforce.repository.LeadRepository;
import com.cyforce.repository.SystemSettingsRepository;
import com.cyforce.repository.TicketRepository;
import com.cyforce.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class DataManagementService {

    private final UserRepository userRepository;
    private final TicketRepository ticketRepository;
    private final LeadRepository leadRepository;
    private final SystemSettingsRepository settingsRepository;
    private final SystemConfigService systemConfigService;
    private final SystemMetricsService systemMetricsService;
    private final DataExportReportService exportReportService;
    private final AuditLogService auditLogService;
    private final RequestUserService requestUserService;
    private final ObjectMapper objectMapper;
    private final Path backupsRoot;

    public DataManagementService(UserRepository userRepository,
                                 TicketRepository ticketRepository,
                                 LeadRepository leadRepository,
                                 SystemSettingsRepository settingsRepository,
                                 SystemConfigService systemConfigService,
                                 SystemMetricsService systemMetricsService,
                                 DataExportReportService exportReportService,
                                 AuditLogService auditLogService,
                                 RequestUserService requestUserService,
                                 @Value("${app.uploads-dir:uploads}") String uploadsDir) {
        this.userRepository = userRepository;
        this.ticketRepository = ticketRepository;
        this.leadRepository = leadRepository;
        this.settingsRepository = settingsRepository;
        this.systemConfigService = systemConfigService;
        this.systemMetricsService = systemMetricsService;
        this.exportReportService = exportReportService;
        this.auditLogService = auditLogService;
        this.requestUserService = requestUserService;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.backupsRoot = Path.of(uploadsDir, "backups").toAbsolutePath().normalize();
    }

    public Map<String, Object> overview(String userId) {
        requireAdmin(userId);
        SystemSettings settings = systemConfigService.getOrCreateDefaults();
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("databaseSize", systemMetricsService.formatDatabaseSize());
        overview.put("databaseSizeBytes", systemMetricsService.databaseTotalBytes());
        overview.put("retentionDays", settings.getDataRetentionDays());
        overview.put("lastBackupAt", settings.getLastBackupAt());
        overview.put("lastBackupSummary", settings.getLastBackupSummary());
        overview.put("lastBackupSize", formatSize(settings.getLastBackupSizeBytes()));
        overview.put("lastBackupAgo", formatAgo(settings.getLastBackupAt()));
        overview.put("backupCount", countBackups());
        return overview;
    }

    public Map<String, Object> updateRetention(String userId, int retentionDays) {
        User admin = requireAdmin(userId);
        SystemSettings settings = systemConfigService.getOrCreateDefaults();
        settings.setDataRetentionDays(Math.max(7, Math.min(365, retentionDays)));
        settings.setUpdatedAt(LocalDateTime.now());
        settingsRepository.save(settings);
        purgeExpiredBackups(settings.getDataRetentionDays());
        auditLogService.log(admin, "DATA_RETENTION_UPDATE", "Data Management",
                "Retention set to " + settings.getDataRetentionDays() + " days", null);
        return overview(userId);
    }

    public Map<String, Object> runBackup(String userId, String clientIp) {
        User admin = requireAdmin(userId);
        SystemSettings settings = systemConfigService.getOrCreateDefaults();

        try {
            Files.createDirectories(backupsRoot);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("backupVersion", 1);
            payload.put("backupAt", LocalDateTime.now());
            payload.put("collections", collectBackupData());

            String filename = "cyforce-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json";
            Path target = backupsRoot.resolve(filename);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), payload);

            long size = Files.size(target);
            settings.setLastBackupAt(LocalDateTime.now());
            settings.setLastBackupSizeBytes(size);
            settings.setLastBackupSummary("Last successful backup completed at "
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("h:mm a")) + " today.");
            settings.setUpdatedAt(LocalDateTime.now());
            settingsRepository.save(settings);

            purgeExpiredBackups(settings.getDataRetentionDays());
            auditLogService.log(admin, "DATA_BACKUP", "Data Management",
                    "Backup created: " + filename + " (" + formatSize(size) + ")", clientIp);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", "Backup completed successfully.");
            result.put("filename", filename);
            result.put("size", formatSize(size));
            result.putAll(overview(userId));
            return result;
        } catch (IOException e) {
            throw new RuntimeException("Backup failed: " + e.getMessage());
        }
    }

    public byte[] exportData(String userId, String format, String clientIp) {
        User admin = requireAdmin(userId);
        String normalized = normalizeFormat(format);
        ExportData data = loadExportData();
        auditLogService.log(admin, "DATA_EXPORT", "Data Management",
                "Exported users, tickets, and leads as " + normalized.toUpperCase(), clientIp);
        if ("pdf".equals(normalized)) {
            return exportReportService.toPdf(data.users(), data.tickets(), data.leads());
        }
        return exportReportService.toCsv(data.users(), data.tickets(), data.leads());
    }

    private ExportData loadExportData() {
        List<Map<String, Object>> users = userRepository.findAll().stream()
                .sorted(Comparator.comparing(User::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::sanitizeUser)
                .toList();
        List<Map<String, Object>> tickets = ticketRepository.findAll().stream()
                .sorted(Comparator.comparing(Ticket::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::sanitizeTicket)
                .toList();
        List<Map<String, Object>> leads = leadRepository.findAll().stream()
                .sorted(Comparator.comparing(Lead::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::sanitizeLead)
                .toList();
        return new ExportData(users, tickets, leads);
    }

    private Map<String, Object> collectBackupData() {
        Map<String, Object> collections = new LinkedHashMap<>();
        collections.put("users", userRepository.findAll().stream().map(this::sanitizeUser).toList());
        collections.put("tickets", ticketRepository.findAll().stream().map(this::sanitizeTicket).toList());
        collections.put("leads", leadRepository.findAll().stream().map(this::sanitizeLead).toList());
        return collections;
    }

    private Map<String, Object> sanitizeUser(User user) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", user.getId());
        row.put("fullName", user.getFullName());
        row.put("email", user.getEmail());
        row.put("phone", user.getPhone());
        row.put("role", user.getRole());
        row.put("companyName", user.getCompanyName());
        row.put("active", user.isActive());
        row.put("createdAt", user.getCreatedAt());
        row.put("lastLoginAt", user.getLastLoginAt());
        return row;
    }

    private Map<String, Object> sanitizeTicket(Ticket ticket) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", ticket.getId());
        row.put("subject", ticket.getSubject());
        row.put("customerName", ticket.getCustomerName());
        row.put("customerEmail", ticket.getCustomerEmail());
        row.put("status", ticket.getStatus());
        row.put("priority", ticket.getPriority());
        row.put("category", ticket.getCategory());
        row.put("assigneeName", ticket.getAssigneeName());
        row.put("createdAt", ticket.getCreatedAt());
        row.put("updatedAt", ticket.getUpdatedAt());
        return row;
    }

    private Map<String, Object> sanitizeLead(Lead lead) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", lead.getId());
        row.put("name", lead.getName());
        row.put("email", lead.getEmail());
        row.put("phone", lead.getPhone());
        row.put("company", lead.getCompany());
        row.put("source", lead.getSource());
        row.put("status", lead.getStatus());
        row.put("ownerName", lead.getOwnerName());
        row.put("createdAt", lead.getCreatedAt());
        return row;
    }

    private void purgeExpiredBackups(int retentionDays) {
        if (!Files.isDirectory(backupsRoot)) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        try (Stream<Path> files = Files.list(backupsRoot)) {
            files.filter(path -> path.getFileName().toString().startsWith("cyforce-backup-"))
                    .forEach(path -> {
                        try {
                            LocalDateTime modified = LocalDateTime.ofInstant(
                                    Files.getLastModifiedTime(path).toInstant(),
                                    java.time.ZoneId.systemDefault());
                            if (modified.isBefore(cutoff)) {
                                Files.deleteIfExists(path);
                            }
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private int countBackups() {
        if (!Files.isDirectory(backupsRoot)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(backupsRoot)) {
            return (int) files.filter(path -> path.getFileName().toString().startsWith("cyforce-backup-")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private String formatAgo(LocalDateTime value) {
        if (value == null) {
            return "Never";
        }
        long minutes = ChronoUnit.MINUTES.between(value, LocalDateTime.now());
        if (minutes < 1) {
            return "Just now";
        }
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = ChronoUnit.HOURS.between(value, LocalDateTime.now());
        if (hours < 48) {
            return hours + "h ago";
        }
        long days = ChronoUnit.DAYS.between(value, LocalDateTime.now());
        return days + "d ago";
    }

    private String formatSize(Long bytes) {
        if (bytes == null || bytes <= 0) {
            return "0 B";
        }
        return systemMetricsService.formatBytes(bytes);
    }

    private String normalizeFormat(String format) {
        String value = format == null ? "csv" : format.trim().toLowerCase();
        if (!"csv".equals(value) && !"pdf".equals(value)) {
            throw new RuntimeException("Unsupported export format. Use csv or pdf.");
        }
        return value;
    }

    private User requireAdmin(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "ADMIN");
        return user;
    }

    private record ExportData(List<Map<String, Object>> users,
                              List<Map<String, Object>> tickets,
                              List<Map<String, Object>> leads) {
    }
}
