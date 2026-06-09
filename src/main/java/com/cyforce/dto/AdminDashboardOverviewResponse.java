package com.cyforce.dto;

import com.cyforce.model.AuditLog;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminDashboardOverviewResponse {
    private DashboardStatsResponse stats;
    private long openTickets;
    private long totalLeads;
    private long activeSessions;
    private long anomalyAlertCount;
    private int storageUsagePercent;
    private Double userGrowthPercent;
    private List<DayActivityItem> registrationActivity;
    private List<UserListItemResponse> pendingApprovalUsers;
    private List<RecentActivityItem> recentActivity;
    private List<AnomalyAlertItem> anomalyAlerts;
    private List<StorageSliceItem> storageBreakdown;
    private List<SystemHealthItem> systemHealth;
    private List<AuditLog> recentAuditLogs;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayActivityItem {
        private String day;
        private long registrations;
        private long logins;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentActivityItem {
        private String userEmail;
        private String action;
        private String module;
        private String createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnomalyAlertItem {
        private String id;
        private String type;
        private String message;
        private String createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StorageSliceItem {
        private String name;
        private int value;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SystemHealthItem {
        private String name;
        private String status;
        private String uptime;
    }
}
