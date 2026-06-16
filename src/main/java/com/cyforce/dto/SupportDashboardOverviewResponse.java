package com.cyforce.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupportDashboardOverviewResponse {
    private StatsItem stats;
    private AgentStatusItem agentStatus;
    private List<TicketItem> priorityTickets;
    private List<TicketItem> activeTickets;
    private PerformanceItem todayPerformance;
    private List<FeedbackItem> recentFeedback;
    private List<ArticleItem> suggestedArticles;
    private List<TeamMemberItem> teamAvailability;
    private List<ActivityItem> recentActivity;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatsItem {
        private long openTickets;
        private String avgResponseTime;
        private long resolvedToday;
        private double satisfactionRating;
        private int slaCompliance;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentStatusItem {
        private String status;
        private String statusSince;
        private String shiftLabel;
        private long statusSeconds;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TicketItem {
        private String id;
        private String ticketNumber;
        private String subject;
        private String priority;
        private String status;
        private String customerName;
        private String customerEmail;
        private String openedAt;
        private String lastUpdate;
        private int slaPercent;
        private String slaRemaining;
        private boolean slaBreached;
        private boolean slaEscalated;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceItem {
        private int target;
        private int achieved;
        private int percent;
        private String estimatedCompletion;
        private String statusMessage;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeedbackItem {
        private String customerName;
        private String companyName;
        private int rating;
        private String comment;
        private String createdAt;
        private String ticketId;
        private String ticketNumber;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ArticleItem {
        private String id;
        private String title;
        private String category;
        private int views;
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
    public static class ActivityItem {
        private String type;
        private String title;
        private String time;
    }
}
