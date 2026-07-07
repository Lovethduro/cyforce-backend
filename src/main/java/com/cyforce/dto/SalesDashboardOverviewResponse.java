package com.cyforce.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SalesDashboardOverviewResponse {
    private StatsItem stats;
    private List<PipelineStageItem> pipeline;
    private List<SourceItem> leadSources;
    private List<LeadItem> hotLeads;
    private List<LeadItem> opportunities;
    private List<ActivityItem> recentActivity;
    private List<TaskItem> upcomingTasks;
    private List<LeaderboardItem> leaderboard;
    private LeaderboardItem myRank;
    private long unreadConversations;
    private List<ConversationItem> recentConversations;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatsItem {
        private long totalLeads;
        private long qualifiedLeads;
        private long convertedLeads;
        private long convertedThisMonth;
        private int monthlyTarget;
        private int monthlyProgressPercent;
        private long pipelineValueKobo;
        private int conversionRate;
        private long commissionKobo;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PipelineStageItem {
        private String key;
        private String label;
        private long count;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceItem {
        private String label;
        private long value;
        private String color;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LeadItem {
        private String id;
        private String name;
        private String email;
        private String phone;
        private String company;
        private String source;
        private String status;
        private int score;
        private long valueKobo;
        private String lastContact;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityItem {
        private String title;
        private String time;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskItem {
        private String id;
        private String time;
        private String type;
        private String company;
        private String contact;
        private String leadId;
        private boolean done;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LeaderboardItem {
        private int rank;
        private String agentId;
        private String agentName;
        private long converted;
        private long qualified;
        private boolean currentUser;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConversationItem {
        private String id;
        private String customerName;
        private String subject;
        private String updatedAt;
    }
}
