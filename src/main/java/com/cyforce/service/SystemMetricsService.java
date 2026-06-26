package com.cyforce.service;

import com.cyforce.config.ApplicationUptime;
import com.cyforce.dto.AdminDashboardOverviewResponse;
import com.cyforce.repository.UserRepository;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SystemMetricsService {

    private static final Map<String, List<String>> STORAGE_GROUPS = new LinkedHashMap<>() {{
        put("Users & accounts", List.of("users"));
        put("Tickets & support", List.of("tickets", "ticket_messages", "ticket_feedback"));
        put("Sales & messaging", List.of("leads", "lead_assignment_logs", "conversations", "conversation_messages"));
        put("Knowledge base", List.of("knowledge_articles"));
        put("Billing & payments", List.of("invoices", "payment_transactions"));
        put("Products & deals", List.of("products", "hot_deals"));
        put("System & audit", List.of(
                "audit_logs",
                "notifications",
                "approval_requests",
                "leave_requests",
                "calendar_events",
                "agent_presence",
                "system_settings",
                "customer_feedback",
                "customer_referrals",
                "motivational_messages",
                "sales_playbook"
        ));
    }};

    private final MongoTemplate mongoTemplate;
    private final JavaMailSender mailSender;
    private final UserRepository userRepository;
    private final ApplicationUptime applicationUptime;
    private final Path uploadsRoot;

    public SystemMetricsService(MongoTemplate mongoTemplate,
                                JavaMailSender mailSender,
                                UserRepository userRepository,
                                ApplicationUptime applicationUptime,
                                @Value("${app.uploads-dir:uploads}") String uploadsDir) {
        this.mongoTemplate = mongoTemplate;
        this.mailSender = mailSender;
        this.userRepository = userRepository;
        this.applicationUptime = applicationUptime;
        this.uploadsRoot = Path.of(uploadsDir).toAbsolutePath().normalize();
    }

    public int storageUsagePercent() {
        Document stats = dbStats();
        if (stats == null) {
            return 0;
        }
        long dataSize = number(stats.get("dataSize"));
        long storageSize = number(stats.get("storageSize"));
        if (storageSize <= 0) {
            return dataSize > 0 ? 1 : 0;
        }
        return (int) Math.min(100, Math.round((dataSize * 100.0) / storageSize));
    }

    public long databaseTotalBytes() {
        Document stats = dbStats();
        if (stats == null) {
            return 0L;
        }
        return number(stats.get("dataSize")) + number(stats.get("indexSize"));
    }

    public String formatDatabaseSize() {
        return formatBytes(databaseTotalBytes());
    }

    public String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public List<AdminDashboardOverviewResponse.StorageSliceItem> storageBreakdown() {
        Set<String> existingCollections = mongoTemplate.getDb().listCollectionNames().into(new java.util.HashSet<>());
        List<GroupMetrics> groups = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : STORAGE_GROUPS.entrySet()) {
            long bytes = 0;
            long documents = 0;
            for (String collection : entry.getValue()) {
                if (!existingCollections.contains(collection)) {
                    continue;
                }
                CollectionStats stats = collectionStats(collection);
                bytes += stats.bytes();
                documents += stats.documents();
            }
            groups.add(new GroupMetrics(entry.getKey(), bytes, documents));
        }

        long uploadBytes = uploadsDirectorySize();
        if (uploadBytes > 0) {
            groups.add(new GroupMetrics("Uploaded files", uploadBytes, 0));
        }

        long totalBytes = groups.stream().mapToLong(GroupMetrics::bytes).sum();
        List<AdminDashboardOverviewResponse.StorageSliceItem> slices = new ArrayList<>();
        for (GroupMetrics group : groups) {
            if (group.bytes() <= 0 && totalBytes > 0) {
                continue;
            }
            int pct = totalBytes > 0 ? percent(group.bytes(), totalBytes) : 0;
            slices.add(new AdminDashboardOverviewResponse.StorageSliceItem(
                    group.name(),
                    pct,
                    formatSizeLabel(group.bytes(), group.documents()),
                    group.bytes()
            ));
        }

        if (slices.isEmpty()) {
            slices.add(new AdminDashboardOverviewResponse.StorageSliceItem(
                    "Database",
                    0,
                    "0 B",
                    0L
            ));
        }
        return slices;
    }

    public List<AdminDashboardOverviewResponse.SystemHealthItem> systemHealth() {
        List<AdminDashboardOverviewResponse.SystemHealthItem> items = new ArrayList<>();

        items.add(new AdminDashboardOverviewResponse.SystemHealthItem(
                "API Server",
                "online",
                "Up " + applicationUptime.formatUptime()
        ));

        HealthCheck database = checkDatabase();
        items.add(new AdminDashboardOverviewResponse.SystemHealthItem(
                "Database",
                database.status(),
                database.detail()
        ));

        HealthCheck email = checkEmail();
        items.add(new AdminDashboardOverviewResponse.SystemHealthItem(
                "Email Service",
                email.status(),
                email.detail()
        ));

        HealthCheck storage = checkStorage();
        items.add(new AdminDashboardOverviewResponse.SystemHealthItem(
                "Storage",
                storage.status(),
                storage.detail()
        ));

        HealthCheck auth = checkAuthentication();
        items.add(new AdminDashboardOverviewResponse.SystemHealthItem(
                "Authentication",
                auth.status(),
                auth.detail()
        ));

        return items;
    }

    private HealthCheck checkDatabase() {
        long start = System.currentTimeMillis();
        try {
            Document result = mongoTemplate.getDb().runCommand(new Document("ping", 1));
            long ms = System.currentTimeMillis() - start;
            boolean ok = result != null && "1.0".equals(String.valueOf(result.get("ok")));
            if (ok) {
                return new HealthCheck("online", "Connected · " + ms + "ms");
            }
            return new HealthCheck("degraded", "Ping returned unexpected response");
        } catch (Exception e) {
            return new HealthCheck("offline", "Unreachable: " + e.getMessage());
        }
    }

    private HealthCheck checkStorage() {
        try {
            Document stats = dbStats();
            if (stats == null) {
                return new HealthCheck("offline", "Stats unavailable");
            }
            long dataSize = number(stats.get("dataSize"));
            long storageSize = number(stats.get("storageSize"));
            return new HealthCheck("online", formatBytes(dataSize) + " data · " + formatBytes(storageSize) + " allocated");
        } catch (Exception e) {
            return new HealthCheck("offline", e.getMessage());
        }
    }

    private HealthCheck checkEmail() {
        if (!(mailSender instanceof JavaMailSenderImpl impl)) {
            return new HealthCheck("degraded", "Mail sender not configured");
        }
        long start = System.currentTimeMillis();
        try {
            impl.testConnection();
            long ms = System.currentTimeMillis() - start;
            return new HealthCheck("online", "SMTP reachable · " + ms + "ms");
        } catch (Exception e) {
            return new HealthCheck("offline", "SMTP error: " + shorten(e.getMessage()));
        }
    }

    private HealthCheck checkAuthentication() {
        long start = System.currentTimeMillis();
        try {
            long count = userRepository.count();
            long ms = System.currentTimeMillis() - start;
            return new HealthCheck("online", count + " accounts · " + ms + "ms");
        } catch (Exception e) {
            return new HealthCheck("offline", shorten(e.getMessage()));
        }
    }

    private Document dbStats() {
        return mongoTemplate.getDb().runCommand(new Document("dbStats", 1));
    }

    private CollectionStats collectionStats(String collection) {
        try {
            Document stats = mongoTemplate.getDb().runCommand(new Document("collStats", collection));
            long bytes = number(stats.get("size"));
            if (bytes <= 0) {
                bytes = number(stats.get("storageSize"));
            }
            long documents = number(stats.get("count"));
            return new CollectionStats(bytes, documents);
        } catch (Exception e) {
            return new CollectionStats(0L, 0L);
        }
    }

    private long uploadsDirectorySize() {
        if (!Files.isDirectory(uploadsRoot)) {
            return 0L;
        }
        try {
            final long[] total = {0L};
            Files.walkFileTree(uploadsRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
            return total[0];
        } catch (IOException e) {
            return 0L;
        }
    }

    private String formatSizeLabel(long bytes, long documents) {
        if (documents > 0) {
            return formatBytes(bytes) + " · " + documents + (documents == 1 ? " record" : " records");
        }
        if (bytes > 0) {
            return formatBytes(bytes);
        }
        return "0 B";
    }

    private int percent(long part, long total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.round((part * 100.0) / total);
    }

    private long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private String shorten(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown error";
        }
        return message.length() > 80 ? message.substring(0, 77) + "..." : message;
    }

    private record CollectionStats(long bytes, long documents) {}

    private record GroupMetrics(String name, long bytes, long documents) {}

    private record HealthCheck(String status, String detail) {}
}
