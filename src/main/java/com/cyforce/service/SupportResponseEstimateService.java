package com.cyforce.service;

import com.cyforce.model.AgentPresence;
import com.cyforce.model.SystemSettings;
import com.cyforce.model.Ticket;
import com.cyforce.repository.AgentPresenceRepository;
import com.cyforce.repository.TicketRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SupportResponseEstimateService {

    private final TicketRepository ticketRepository;
    private final AgentPresenceRepository presenceRepository;
    private final TicketMetricsService metricsService;
    private final SystemConfigService systemConfigService;

    public SupportResponseEstimateService(TicketRepository ticketRepository,
                                          AgentPresenceRepository presenceRepository,
                                          TicketMetricsService metricsService,
                                          SystemConfigService systemConfigService) {
        this.ticketRepository = ticketRepository;
        this.presenceRepository = presenceRepository;
        this.metricsService = metricsService;
        this.systemConfigService = systemConfigService;
    }

    public Map<String, Object> estimate(String priority) {
        String normalized = normalizePriority(priority);
        SystemSettings settings = systemConfigService.getOrCreateDefaults();

        int openQueue = (int) ticketRepository.findByStatusInOrderByCreatedAtDesc(List.of("open", "in_progress")).stream()
                .filter(t -> !"merged".equalsIgnoreCase(t.getStatus()))
                .count();
        int availableAgents = Math.max(1, countAvailableAgents());
        double teamAvgHours = computeTeamAvgFirstResponseHours();
        double priorityCapHours = metricsService.slaHoursForPriority(normalized) * 0.5;
        double queueHours = (openQueue / (double) availableAgents) * teamAvgHours;
        double estimateHours = Math.min(priorityCapHours, Math.max(0.5, (teamAvgHours * 0.75) + (queueHours * 0.5)));
        int estimateMinutes = (int) Math.max(30, Math.round(estimateHours * 60));

        String confidence;
        if (availableAgents >= 2 && openQueue <= 8) {
            confidence = "high";
        } else if (openQueue >= 20 || availableAgents == 1) {
            confidence = "low";
        } else {
            confidence = "medium";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("priority", normalized);
        result.put("estimatedLabel", formatDuration(estimateMinutes));
        result.put("estimatedMinutes", estimateMinutes);
        result.put("policyLabel", policyLabel(normalized, settings));
        result.put("confidence", confidence);
        result.put("openQueue", openQueue);
        result.put("availableAgents", availableAgents);
        result.put("teamAvgResponseHours", teamAvgHours);
        return result;
    }

    private int countAvailableAgents() {
        List<AgentPresence> team = presenceRepository.findByTeam("support");
        if (team == null || team.isEmpty()) {
            return 1;
        }
        long available = team.stream()
                .filter(p -> p.getStatus() != null && p.getStatus().equalsIgnoreCase("available"))
                .filter(p -> p.getRole() != null && ("SUPPORT_AGENT".equalsIgnoreCase(p.getRole())
                        || "SUPERVISOR".equalsIgnoreCase(p.getRole())))
                .count();
        return available > 0 ? (int) available : 1;
    }

    private double computeTeamAvgFirstResponseHours() {
        List<Ticket> recent = ticketRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(t -> t.getCreatedAt() != null && t.getCreatedAt().isAfter(LocalDateTime.now().minusDays(30)))
                .limit(120)
                .toList();

        double total = 0;
        int counted = 0;
        for (Ticket ticket : recent) {
            Double hours = metricsService.firstResponseHours(ticket, ticket.getAssigneeId());
            if (hours != null) {
                total += hours;
                counted++;
            }
        }
        if (counted == 0) {
            return 2.0;
        }
        return Math.round((total / counted) * 10.0) / 10.0;
    }

    private String policyLabel(String priority, SystemSettings settings) {
        return switch (priority) {
            case "urgent", "critical" -> settings.getSlaUrgent();
            case "high" -> settings.getSlaHigh();
            case "low" -> settings.getSlaLow();
            default -> settings.getSlaMedium();
        };
    }

    private String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return "medium";
        }
        return priority.toLowerCase().trim();
    }

    private String formatDuration(int minutes) {
        if (minutes < 60) {
            return "~" + minutes + " min";
        }
        int hours = minutes / 60;
        int mins = minutes % 60;
        if (mins == 0) {
            return "~" + hours + "h";
        }
        return "~" + hours + "h " + mins + "m";
    }
}
