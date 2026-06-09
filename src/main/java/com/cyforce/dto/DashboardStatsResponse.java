package com.cyforce.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsResponse {
    private long totalUsers;
    private long activeUsers;
    private long pendingApprovals;
    private long mfaEnabledUsers;
    private long verifiedUsers;
    private Map<String, Long> usersByRole;
    private List<UserListItemResponse> recentUsers;
}
