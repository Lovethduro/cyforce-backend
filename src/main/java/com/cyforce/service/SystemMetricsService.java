package com.cyforce.service;

import com.cyforce.config.ApplicationUptime;
import com.cyforce.dto.AdminDashboardOverviewResponse;
import com.cyforce.repository.UserRepository;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SystemMetricsService {

    private static final Map<String, String> COLLECTION_LABELS = new LinkedHashMap<>() {{
        put("users", "Customer Data");
        put("invoices", "Documents");
        put("tickets", "Tickets");
        put("knowledge_articles", "Knowledge Base");
        put("payment_transactions", "Other");
    }};

    private final MongoTemplate mongoTemplate;
    private final JavaMailSender mailSender;
    private final UserRepository userRepository;
    private final ApplicationUptime applicationUptime;

    public SystemMetricsService(MongoTemplate mongoTemplate,
                                JavaMailSender mailSender,
                                UserRepository userRepository,
                                ApplicationUptime applicationUptime) {
        this.mongoTemplate = mongoTemplate;
        this.mailSender = mailSender;
        this.userRepository = userRepository;
        this.applicationUptime = applicationUptime;
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

    public List<AdminDashboardOverviewResponse.StorageSliceItem> storageBreakdown() {
        Map<String, Long> sizes = new LinkedHashMap<>();
        long total = 0;
        for (String collection : COLLECTION_LABELS.keySet()) {
            long size = collectionSizeBytes(collection);
            sizes.put(collection, size);
            total += size;
        }
        if (total == 0) {
            return COLLECTION_LABELS.values().stream()
                    .map(label -> new AdminDashboardOverviewResponse.StorageSliceItem(label, 0))
                    .toList();
        }
        List<AdminDashboardOverviewResponse.StorageSliceItem> slices = new ArrayList<>();
        for (Map.Entry<String, String> entry : COLLECTION_LABELS.entrySet()) {
            long size = sizes.getOrDefault(entry.getKey(), 0L);
            slices.add(new AdminDashboardOverviewResponse.StorageSliceItem(
                    entry.getValue(),
                    percent(size, total)
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
            return new HealthCheck("online", formatBytes(dataSize) + " / " + formatBytes(storageSize));
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

    private long collectionSizeBytes(String collection) {
        try {
            Document stats = mongoTemplate.getDb().runCommand(new Document("collStats", collection));
            return number(stats.get("storageSize"));
        } catch (Exception e) {
            return 0L;
        }
    }

    private int percent(long part, long total) {
        return (int) Math.round((part * 100.0) / total);
    }

    private long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private String formatBytes(long bytes) {
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

    private String shorten(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown error";
        }
        return message.length() > 80 ? message.substring(0, 77) + "..." : message;
    }

    private record HealthCheck(String status, String detail) {}
}
