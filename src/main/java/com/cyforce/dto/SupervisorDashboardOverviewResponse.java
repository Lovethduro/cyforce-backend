package com.cyforce.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupervisorDashboardOverviewResponse {
    private StatsItem stats;
    private List<DayVolumeItem> ticketVolumeTrend;
    private List<WorkloadItem> agentWorkload;
    private List<ApprovalItem> pendingApprovals;
    private List<LeaderboardItem> teamLeaderboard;
    private int slaCompliancePercent;
    private List<AlertItem> recentAlerts;
    private List<TeamMemberItem> teamAvailability;
    private List<FeedbackItem> recentFeedback;
    private List<StatusSliceItem> ticketsByStatus;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatsItem {
        private long openTickets;
        private String avgResponseTime;
        private String avgResolutionTime;
        private double customerSatisfaction;
        private int salesTarget;
        private int salesAchieved;
        private long openEscalations;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayVolumeItem {
        private String date;
        private String label;
        private long total;
        private long resolved;
        private long open;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkloadItem {
        private String agentId;
        private String agentName;
        private long openTickets;
        private boolean overloaded;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalItem {
        private String id;
        private String name;
        private String email;
        private String role;
        private String type;
        private String createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LeaderboardItem {
        private int rank;
        private String name;
        private long ticketsResolved;
        private String avgResponse;
        private double rating;
        private String medal;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlertItem {
        private String id;
        private String type;
        private String priority;
        private String message;
        private String createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TeamMemberItem {
        private String userId;
        private String name;
        private String status;
        private String team;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeedbackItem {
        private String companyName;
        private String customerName;
        private int rating;
        private String comment;
        private String createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusSliceItem {
        private String label;
        private long value;
    }
}
