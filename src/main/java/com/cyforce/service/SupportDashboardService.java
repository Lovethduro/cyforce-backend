package com.cyforce.service;

import com.cyforce.dto.SupportDashboardOverviewResponse;
import com.cyforce.model.*;
import com.cyforce.repository.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SupportDashboardService {

    private final RequestUserService requestUserService;
    private final TicketRepository ticketRepository;
    private final TicketMessageRepository messageRepository;
    private final TicketFeedbackRepository feedbackRepository;
    private final KnowledgeArticleRepository articleRepository;
    private final AgentPresenceRepository presenceRepository;
    private final UserRepository userRepository;
    private final TicketMetricsService metricsService;
    private final AuditLogRepository auditLogRepository;

    public SupportDashboardService(RequestUserService requestUserService,
                                   TicketRepository ticketRepository,
                                   TicketMessageRepository messageRepository,
                                   TicketFeedbackRepository feedbackRepository,
                                   KnowledgeArticleRepository articleRepository,
                                   AgentPresenceRepository presenceRepository,
                                   UserRepository userRepository,
                                   TicketMetricsService metricsService,
                                   AuditLogRepository auditLogRepository) {
        this.requestUserService = requestUserService;
        this.ticketRepository = ticketRepository;
        this.messageRepository = messageRepository;
        this.feedbackRepository = feedbackRepository;
        this.articleRepository = articleRepository;
        this.presenceRepository = presenceRepository;
        this.userRepository = userRepository;
        this.metricsService = metricsService;
        this.auditLogRepository = auditLogRepository;
    }

    public SupportDashboardOverviewResponse overview(String userId) {
        User agent = requestUserService.requireUser(userId);
        requestUserService.requireRole(agent, "SUPPORT_AGENT", "ADMIN", "SUPERVISOR");

        List<Ticket> myTickets = ticketRepository.findTop200ByAssigneeIdOrderByCreatedAtDesc(agent.getId());
        List<Ticket> openMine = myTickets.stream()
                .filter(t -> "open".equals(t.getStatus()) || "in_progress".equals(t.getStatus()))
                .toList();

        LocalDate today = LocalDate.now();
        long resolvedToday = myTickets.stream()
                .filter(t -> ("resolved".equals(t.getStatus()) || "closed".equals(t.getStatus()))
                        && t.getUpdatedAt() != null && t.getUpdatedAt().toLocalDate().equals(today))
                .count();

        List<TicketFeedback> myFeedback = feedbackRepository.findTop20ByAssigneeIdOrderByCreatedAtDesc(agent.getId());
        double satisfaction = myFeedback.isEmpty() ? 0
                : Math.round(myFeedback.stream().mapToInt(TicketFeedback::getRating).average().orElse(0) * 10.0) / 10.0;

        AgentPresence presence = ensurePresence(agent);

        List<Ticket> priorityPool = openMine.stream()
                .sorted(Comparator.comparingInt((Ticket t) -> effectivePriorityWeight(t)).reversed()
                        .thenComparing(Ticket::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(5)
                .toList();

        int dailyTarget = 8;
        int achieved = (int) resolvedToday;
        int percent = dailyTarget > 0 ? Math.min(100, (int) Math.round((achieved * 100.0) / dailyTarget)) : 0;

        return new SupportDashboardOverviewResponse(
                new SupportDashboardOverviewResponse.StatsItem(
                        openMine.size(),
                        metricsService.formatHours(metricsService.avgResponseHours(myTickets, agent.getId())),
                        resolvedToday,
                        satisfaction > 0 ? satisfaction : computeTeamSatisfaction(),
                        metricsService.slaCompliancePercent(myTickets)
                ),
                toAgentStatus(presence),
                priorityPool.stream().map(this::toTicketItem).toList(),
                openMine.stream().limit(10).map(this::toTicketItem).toList(),
                new SupportDashboardOverviewResponse.PerformanceItem(
                        dailyTarget,
                        achieved,
                        percent,
                        estimateCompletion(achieved, dailyTarget),
                        performanceStatusMessage(achieved, dailyTarget)
                ),
                buildFeedback(myFeedback),
                buildArticles(),
                buildTeamAvailability(),
                buildActivity(agent.getId())
        );
    }

    public AgentPresence updateStatus(String userId, String status) {
        User agent = requestUserService.requireUser(userId);
        requestUserService.requireRole(agent, "SUPPORT_AGENT", "ADMIN", "SUPERVISOR");
        AgentPresence presence = ensurePresence(agent);
        presence.setStatus(normalizeStatus(status));
        presence.setStatusSince(LocalDateTime.now());
        presence.setUpdatedAt(LocalDateTime.now());
        return presenceRepository.save(presence);
    }

    private AgentPresence ensurePresence(User agent) {
        return presenceRepository.findByUserId(agent.getId()).orElseGet(() -> {
            AgentPresence p = new AgentPresence();
            p.setUserId(agent.getId());
            p.setFullName(agent.getFullName());
            p.setRole(agent.getRole());
            p.setTeam("support");
            p.setStatus("available");
            p.setStatusSince(LocalDateTime.now());
            p.setShiftLabel("Morning Shift · 8:00 AM – 4:00 PM");
            p.setUpdatedAt(LocalDateTime.now());
            return presenceRepository.save(p);
        });
    }

    private SupportDashboardOverviewResponse.AgentStatusItem toAgentStatus(AgentPresence p) {
        long seconds = p.getStatusSince() == null ? 0
                : Duration.between(p.getStatusSince(), LocalDateTime.now()).getSeconds();
        return new SupportDashboardOverviewResponse.AgentStatusItem(
                p.getStatus(),
                metricsService.formatRelative(p.getStatusSince()),
                p.getShiftLabel(),
                seconds
        );
    }

    private SupportDashboardOverviewResponse.TicketItem toTicketItem(Ticket t) {
        return new SupportDashboardOverviewResponse.TicketItem(
                t.getId(),
                metricsService.ticketNumber(t),
                t.getSubject(),
                t.getPriority(),
                t.getStatus(),
                t.getCustomerName(),
                t.getCustomerEmail(),
                metricsService.formatRelative(t.getCreatedAt()),
                metricsService.formatRelative(t.getUpdatedAt() != null ? t.getUpdatedAt() : t.getCreatedAt()),
                metricsService.slaProgressPercent(t),
                metricsService.slaRemainingLabel(t),
                metricsService.isSlaBreached(t),
                t.isSlaEscalated()
        );
    }

    private int priorityWeight(String priority) {
        if (priority == null) return 1;
        return switch (priority.toLowerCase()) {
            case "high", "urgent", "critical" -> 3;
            case "medium" -> 2;
            default -> 1;
        };
    }

    private int effectivePriorityWeight(Ticket ticket) {
        int base = priorityWeight(ticket.getPriority());
        return base + (metricsService.isSlaBreached(ticket) ? 10 : 0);
    }

    private double computeTeamSatisfaction() {
        List<TicketFeedback> recent = feedbackRepository.findTop100ByOrderByCreatedAtDesc();
        if (recent.isEmpty()) return 4.5;
        return Math.round(recent.stream().mapToInt(TicketFeedback::getRating).average().orElse(0) * 10.0) / 10.0;
    }

    private List<SupportDashboardOverviewResponse.FeedbackItem> buildFeedback(List<TicketFeedback> feedback) {
        List<TicketFeedback> recent = feedback.stream().limit(5).toList();
        List<String> ticketIds = recent.stream()
                .map(TicketFeedback::getTicketId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        Map<String, String> ticketNumbers = ticketRepository.findAllById(ticketIds).stream()
                .collect(Collectors.toMap(Ticket::getId, metricsService::ticketNumber, (a, b) -> a));

        return recent.stream().map(f -> new SupportDashboardOverviewResponse.FeedbackItem(
                f.getCustomerName(),
                f.getCompanyName(),
                f.getRating(),
                f.getComment(),
                metricsService.formatIso(f.getCreatedAt()),
                f.getTicketId(),
                ticketNumbers.get(f.getTicketId())
        )).toList();
    }

    private List<SupportDashboardOverviewResponse.ArticleItem> buildArticles() {
        return articleRepository.findByPublishedTrueOrderByViewsDesc().stream()
                .limit(5)
                .map(a -> new SupportDashboardOverviewResponse.ArticleItem(a.getId(), a.getTitle(), a.getCategory(), a.getViews()))
                .toList();
    }

    private List<SupportDashboardOverviewResponse.TeamMemberItem> buildTeamAvailability() {
        List<AgentPresence> all = presenceRepository.findAllByOrderByFullNameAsc();
        if (all.isEmpty()) {
            return userRepository.findByRoleIn(List.of("SUPPORT_AGENT", "SUPERVISOR")).stream()
                    .map(u -> new SupportDashboardOverviewResponse.TeamMemberItem(
                            u.getId(), u.getFullName(), "offline", "support"))
                    .toList();
        }
        return all.stream()
                .limit(8)
                .map(p -> new SupportDashboardOverviewResponse.TeamMemberItem(
                        p.getUserId(), p.getFullName(), p.getStatus(), p.getTeam()))
                .toList();
    }

    private List<SupportDashboardOverviewResponse.ActivityItem> buildActivity(String userId) {
        List<SupportDashboardOverviewResponse.ActivityItem> items = new ArrayList<>();

        messageRepository.findTop5ByAuthorIdOrderByCreatedAtDesc(userId)
                .forEach(m -> items.add(new SupportDashboardOverviewResponse.ActivityItem(
                        "reply", "Replied to ticket", metricsService.formatRelative(m.getCreatedAt()))));

        auditLogRepository.findTop10ByActionInOrderByCreatedAtDesc(List.of("TICKET_CREATE", "TICKET_UPDATE"))
                .forEach(log -> items.add(new SupportDashboardOverviewResponse.ActivityItem(
                        "update", log.getDetails() != null ? log.getDetails() : log.getAction(),
                        metricsService.formatRelative(log.getCreatedAt()))));

        return items.stream().limit(8).collect(Collectors.toList());
    }

    private String normalizeStatus(String status) {
        if (status == null) return "available";
        return switch (status.toLowerCase().replace(" ", "_")) {
            case "busy" -> "busy";
            case "away" -> "away";
            case "on_break", "break" -> "on_break";
            case "unavailable", "offline" -> "unavailable";
            case "online" -> "online";
            default -> "available";
        };
    }

    private String performanceStatusMessage(int achieved, int target) {
        if (target <= 0) {
            return "No target set";
        }
        if (achieved >= target) {
            return "Target reached!";
        }
        if (achieved >= Math.max(1, (int) Math.ceil(target * 0.6))) {
            return "You're on track!";
        }
        return "More tickets to close today";
    }

    private String estimateCompletion(int achieved, int target) {
        if (achieved >= target) {
            return "Done for today";
        }
        int remaining = Math.max(target - achieved, 1);
        LocalDateTime estimate = LocalDateTime.now().plusMinutes(remaining * 45L);
        return estimate.format(DateTimeFormatter.ofPattern("h:mm a"));
    }
}
