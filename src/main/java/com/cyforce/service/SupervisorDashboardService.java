package com.cyforce.service;

import com.cyforce.dto.SupervisorDashboardOverviewResponse;
import com.cyforce.model.*;
import com.cyforce.repository.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class SupervisorDashboardService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final int SALES_MONTHLY_TARGET = 20;
    private static final int WORKLOAD_THRESHOLD = 5;

    private final RequestUserService requestUserService;
    private final UserRepository userRepository;
    private final TicketRepository ticketRepository;
    private final LeadRepository leadRepository;
    private final TicketFeedbackRepository feedbackRepository;
    private final AgentPresenceRepository presenceRepository;
    private final NotificationRepository notificationRepository;
    private final TicketMetricsService metricsService;
    public SupervisorDashboardService(RequestUserService requestUserService,
                                      UserRepository userRepository,
                                      TicketRepository ticketRepository,
                                      LeadRepository leadRepository,
                                      TicketFeedbackRepository feedbackRepository,
                                      AgentPresenceRepository presenceRepository,
                                      NotificationRepository notificationRepository,
                                      TicketMetricsService metricsService) {
        this.requestUserService = requestUserService;
        this.userRepository = userRepository;
        this.ticketRepository = ticketRepository;
        this.leadRepository = leadRepository;
        this.feedbackRepository = feedbackRepository;
        this.presenceRepository = presenceRepository;
        this.notificationRepository = notificationRepository;
        this.metricsService = metricsService;
    }

    public SupervisorDashboardOverviewResponse overview(String userId, String teamFilter) {
        User supervisor = requestUserService.requireUser(userId);
        requestUserService.requireRole(supervisor, "SUPERVISOR", "ADMIN");

        List<Ticket> allTickets = filterTicketsByTeam(ticketRepository.findAllByOrderByCreatedAtDesc(), teamFilter);
        List<Lead> allLeads = filterLeadsByTeam(leadRepository.findAllByOrderByCreatedAtDesc(), teamFilter);
        List<User> staff = filterStaffByTeam(userRepository.findAll(), teamFilter);

        long openTickets = allTickets.stream()
                .filter(t -> "open".equals(t.getStatus()) || "in_progress".equals(t.getStatus()))
                .count();

        double avgResponse = metricsService.avgResponseHours(allTickets, null);
        double avgResolution = metricsService.avgResolutionHours(allTickets);

        List<TicketFeedback> feedback = feedbackRepository.findAllByOrderByCreatedAtDesc();
        double satisfaction = feedback.isEmpty() ? 0
                : Math.round(feedback.stream().mapToInt(TicketFeedback::getRating).average().orElse(0) * 10.0) / 10.0;

        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        long salesAchieved = allLeads.stream()
                .filter(l -> "converted".equals(l.getStatus()))
                .filter(l -> l.getUpdatedAt() != null && !l.getUpdatedAt().toLocalDate().isBefore(monthStart))
                .count();

        long escalations = allTickets.stream().filter(this::isEscalation).count();

        return new SupervisorDashboardOverviewResponse(
                new SupervisorDashboardOverviewResponse.StatsItem(
                        openTickets,
                        metricsService.formatHours(avgResponse),
                        metricsService.formatHours(avgResolution),
                        satisfaction > 0 ? satisfaction : 4.5,
                        SALES_MONTHLY_TARGET,
                        (int) salesAchieved,
                        escalations
                ),
                buildVolumeTrend(allTickets),
                buildWorkload(staff, allTickets),
                buildPendingApprovals(),
                buildLeaderboard(staff, allTickets, feedback),
                metricsService.slaCompliancePercent(allTickets),
                buildAlerts(allTickets, supervisor.getId()),
                buildTeamAvailability(teamFilter),
                buildFeedback(feedback),
                buildStatusSlices(allTickets)
        );
    }

    private List<Ticket> filterTicketsByTeam(List<Ticket> tickets, String teamFilter) {
        if (teamFilter == null || teamFilter.isBlank() || "all".equalsIgnoreCase(teamFilter)) {
            return tickets;
        }
        if ("support".equalsIgnoreCase(teamFilter)) {
            return tickets;
        }
        return List.of();
    }

    private List<Lead> filterLeadsByTeam(List<Lead> leads, String teamFilter) {
        if (teamFilter == null || teamFilter.isBlank() || "all".equalsIgnoreCase(teamFilter)) {
            return leads;
        }
        if ("sales".equalsIgnoreCase(teamFilter)) {
            return leads;
        }
        return List.of();
    }

    private List<User> filterStaffByTeam(List<User> users, String teamFilter) {
        List<User> staff = users.stream()
                .filter(u -> {
                    String role = u.getRole() == null ? "" : u.getRole().toUpperCase();
                    return role.equals("SUPPORT_AGENT") || role.equals("SALES_AGENT") || role.equals("SUPERVISOR");
                })
                .toList();
        if (teamFilter == null || teamFilter.isBlank() || "all".equalsIgnoreCase(teamFilter)) {
            return staff;
        }
        if ("support".equalsIgnoreCase(teamFilter)) {
            return staff.stream().filter(u -> "SUPPORT_AGENT".equalsIgnoreCase(u.getRole())).toList();
        }
        if ("sales".equalsIgnoreCase(teamFilter)) {
            return staff.stream().filter(u -> "SALES_AGENT".equalsIgnoreCase(u.getRole())).toList();
        }
        return staff;
    }

    private boolean isEscalation(Ticket ticket) {
        if (!"open".equals(ticket.getStatus()) && !"in_progress".equals(ticket.getStatus())) {
            return false;
        }
        if (ticket.getCreatedAt() == null) return false;
        long hours = Duration.between(ticket.getCreatedAt(), LocalDateTime.now()).toHours();
        if ("high".equalsIgnoreCase(ticket.getPriority()) && hours > 4) return true;
        return hours > 48;
    }

    private List<SupervisorDashboardOverviewResponse.DayVolumeItem> buildVolumeTrend(List<Ticket> tickets) {
        List<SupervisorDashboardOverviewResponse.DayVolumeItem> items = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 13; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            String label = day.getDayOfMonth() + " " + day.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            long total = tickets.stream().filter(t -> createdOn(t, day)).count();
            long resolved = tickets.stream()
                    .filter(t -> updatedOn(t, day) && ("resolved".equals(t.getStatus()) || "closed".equals(t.getStatus())))
                    .count();
            long open = tickets.stream()
                    .filter(t -> createdOnOrBefore(t, day))
                    .filter(t -> !resolvedBefore(t, day))
                    .count();
            items.add(new SupervisorDashboardOverviewResponse.DayVolumeItem(
                    day.toString(), label, total, resolved, open));
        }
        return items;
    }

    private boolean createdOn(Ticket t, LocalDate day) {
        return t.getCreatedAt() != null && t.getCreatedAt().toLocalDate().equals(day);
    }

    private boolean updatedOn(Ticket t, LocalDate day) {
        return t.getUpdatedAt() != null && t.getUpdatedAt().toLocalDate().equals(day);
    }

    private boolean createdOnOrBefore(Ticket t, LocalDate day) {
        return t.getCreatedAt() != null && !t.getCreatedAt().toLocalDate().isAfter(day);
    }

    private boolean resolvedBefore(Ticket t, LocalDate day) {
        if (!"resolved".equals(t.getStatus()) && !"closed".equals(t.getStatus())) return false;
        return t.getUpdatedAt() != null && t.getUpdatedAt().toLocalDate().isBefore(day);
    }

    private List<SupervisorDashboardOverviewResponse.WorkloadItem> buildWorkload(List<User> staff, List<Ticket> tickets) {
        return staff.stream()
                .filter(u -> "SUPPORT_AGENT".equalsIgnoreCase(u.getRole()))
                .map(agent -> {
                    long open = tickets.stream()
                            .filter(t -> agent.getId().equals(t.getAssigneeId()))
                            .filter(t -> "open".equals(t.getStatus()) || "in_progress".equals(t.getStatus()))
                            .count();
                    return new SupervisorDashboardOverviewResponse.WorkloadItem(
                            agent.getId(), agent.getFullName(), open, open > WORKLOAD_THRESHOLD);
                })
                .sorted(Comparator.comparingLong(SupervisorDashboardOverviewResponse.WorkloadItem::getOpenTickets).reversed())
                .toList();
    }

    private List<SupervisorDashboardOverviewResponse.ApprovalItem> buildPendingApprovals() {
        return userRepository.findAll().stream()
                .filter(u -> !u.isEmailVerified() || !u.isActive())
                .limit(10)
                .map(u -> new SupervisorDashboardOverviewResponse.ApprovalItem(
                        u.getId(),
                        u.getFullName(),
                        u.getEmail(),
                        u.getRole(),
                        !u.isEmailVerified() ? "User Registration" : "Account Activation",
                        u.getCreatedAt() == null ? null : u.getCreatedAt().format(ISO)
                ))
                .toList();
    }

    private List<SupervisorDashboardOverviewResponse.LeaderboardItem> buildLeaderboard(
            List<User> staff, List<Ticket> tickets, List<TicketFeedback> feedback) {

        List<SupervisorDashboardOverviewResponse.LeaderboardItem> rows = staff.stream()
                .filter(u -> "SUPPORT_AGENT".equalsIgnoreCase(u.getRole()))
                .map(agent -> {
                    List<Ticket> agentTickets = tickets.stream()
                            .filter(t -> agent.getId().equals(t.getAssigneeId()))
                            .toList();
                    long resolved = agentTickets.stream()
                            .filter(t -> "resolved".equals(t.getStatus()) || "closed".equals(t.getStatus()))
                            .count();
                    double avgResp = metricsService.avgResponseHours(agentTickets, agent.getId());
                    double rating = feedback.stream()
                            .filter(f -> agent.getId().equals(f.getAssigneeId()))
                            .mapToInt(TicketFeedback::getRating)
                            .average()
                            .orElse(4.5);
                    return new Object[]{agent.getFullName(), resolved, metricsService.formatHours(avgResp), rating};
                })
                .sorted((a, b) -> Long.compare((long) b[1], (long) a[1]))
                .map(row -> new SupervisorDashboardOverviewResponse.LeaderboardItem(
                        0,
                        (String) row[0],
                        (long) row[1],
                        (String) row[2],
                        Math.round((double) row[3] * 10.0) / 10.0,
                        ""
                ))
                .collect(Collectors.toCollection(ArrayList::new));

        for (int i = 0; i < rows.size(); i++) {
            SupervisorDashboardOverviewResponse.LeaderboardItem item = rows.get(i);
            item.setRank(i + 1);
            item.setMedal(i == 0 ? "gold" : i == 1 ? "silver" : i == 2 ? "bronze" : "");
        }
        return rows;
    }

    private List<SupervisorDashboardOverviewResponse.AlertItem> buildAlerts(List<Ticket> tickets, String supervisorId) {
        List<SupervisorDashboardOverviewResponse.AlertItem> alerts = new ArrayList<>();

        tickets.stream().filter(this::isEscalation).limit(5).forEach(t ->
                alerts.add(new SupervisorDashboardOverviewResponse.AlertItem(
                        "esc-" + t.getId(),
                        "Escalation",
                        t.getPriority(),
                        "Ticket " + metricsService.ticketNumber(t) + " requires attention: " + t.getSubject(),
                        metricsService.formatIso(t.getUpdatedAt() != null ? t.getUpdatedAt() : t.getCreatedAt())
                )));

        tickets.stream().filter(metricsService::isSlaBreached)
                .filter(t -> "open".equals(t.getStatus()) || "in_progress".equals(t.getStatus()))
                .limit(3).forEach(t ->
                        alerts.add(new SupervisorDashboardOverviewResponse.AlertItem(
                                "sla-" + t.getId(),
                                "SLA Breach",
                                "critical",
                                "SLA breached on " + metricsService.ticketNumber(t),
                                metricsService.formatIso(t.getCreatedAt())
                        )));

        notificationRepository.findByUserIdOrderByCreatedAtDesc(supervisorId).stream()
                .filter(n -> {
                    String type = n.getType() == null ? "" : n.getType().toLowerCase();
                    return type.equals("warning") || type.equals("critical") || type.equals("error");
                })
                .limit(3)
                .forEach(n -> alerts.add(new SupervisorDashboardOverviewResponse.AlertItem(
                        "n-" + n.getId(),
                        "System",
                        n.getType(),
                        n.getMessage() != null ? n.getMessage() : n.getTitle(),
                        metricsService.formatIso(n.getCreatedAt())
                )));

        return alerts.stream().limit(8).toList();
    }

    private List<SupervisorDashboardOverviewResponse.TeamMemberItem> buildTeamAvailability(String teamFilter) {
        List<AgentPresence> presence = presenceRepository.findAllByOrderByFullNameAsc();
        Stream<AgentPresence> stream = presence.stream();
        if ("support".equalsIgnoreCase(teamFilter)) {
            stream = stream.filter(p -> "support".equalsIgnoreCase(p.getTeam()));
        } else if ("sales".equalsIgnoreCase(teamFilter)) {
            stream = stream.filter(p -> "sales".equalsIgnoreCase(p.getTeam()));
        }
        List<SupervisorDashboardOverviewResponse.TeamMemberItem> items = stream
                .limit(6)
                .map(p -> new SupervisorDashboardOverviewResponse.TeamMemberItem(
                        p.getUserId(), p.getFullName(), p.getStatus(), p.getTeam()))
                .collect(Collectors.toCollection(ArrayList::new));

        if (items.size() < 6) {
            userRepository.findAll().stream()
                    .filter(u -> "SUPPORT_AGENT".equalsIgnoreCase(u.getRole()) || "SALES_AGENT".equalsIgnoreCase(u.getRole()))
                    .filter(u -> items.stream().noneMatch(i -> i.getUserId().equals(u.getId())))
                    .limit(6 - items.size())
                    .forEach(u -> items.add(new SupervisorDashboardOverviewResponse.TeamMemberItem(
                            u.getId(), u.getFullName(), "offline", "SALES_AGENT".equalsIgnoreCase(u.getRole()) ? "sales" : "support")));
        }
        return items;
    }

    private List<SupervisorDashboardOverviewResponse.FeedbackItem> buildFeedback(List<TicketFeedback> feedback) {
        return feedback.stream().limit(6).map(f -> new SupervisorDashboardOverviewResponse.FeedbackItem(
                f.getCompanyName(),
                f.getCustomerName(),
                f.getRating(),
                f.getComment(),
                metricsService.formatIso(f.getCreatedAt())
        )).toList();
    }

    private List<SupervisorDashboardOverviewResponse.StatusSliceItem> buildStatusSlices(List<Ticket> tickets) {
        Map<String, Long> counts = tickets.stream()
                .collect(Collectors.groupingBy(t -> normalizeStatus(t.getStatus()), Collectors.counting()));
        return List.of("Open", "In Progress", "Resolved", "Closed").stream()
                .map(label -> new SupervisorDashboardOverviewResponse.StatusSliceItem(
                        label, counts.getOrDefault(label.toLowerCase().replace(" ", "_"), 0L)))
                .toList();
    }

    private String normalizeStatus(String status) {
        if (status == null) return "open";
        return switch (status.toLowerCase()) {
            case "in_progress" -> "in_progress";
            case "resolved" -> "resolved";
            case "closed" -> "closed";
            default -> "open";
        };
    }
}
