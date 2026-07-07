package com.cyforce.service;

import com.cyforce.model.Lead;
import com.cyforce.model.Ticket;
import com.cyforce.model.TicketFeedback;
import com.cyforce.model.User;
import com.cyforce.repository.CustomerReferralRepository;
import com.cyforce.repository.LeadRepository;
import com.cyforce.repository.TicketFeedbackRepository;
import com.cyforce.repository.TicketRepository;
import com.cyforce.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    private static final Map<String, String> SOURCE_LABELS = Map.of(
            "website", "Website",
            "referral", "Referral",
            "linkedin", "LinkedIn",
            "event", "Events",
            "social", "Social",
            "cold_call", "Cold Call"
    );

    private static final List<String> LEAD_STAGES = List.of("new", "contacted", "qualified", "converted", "lost");

    private static final Map<String, String> LEAD_STAGE_LABELS = Map.of(
            "new", "New",
            "contacted", "Contacted",
            "qualified", "Qualified",
            "converted", "Converted",
            "lost", "Lost"
    );

    private static final Map<String, String> TICKET_STATUS_LABELS = Map.of(
            "open", "Open",
            "in_progress", "In Progress",
            "pending", "Pending",
            "resolved", "Resolved",
            "closed", "Closed"
    );

    private final LeadRepository leadRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final CustomerReferralRepository referralRepository;
    private final TicketFeedbackRepository feedbackRepository;
    private final RequestUserService requestUserService;

    public AnalyticsService(LeadRepository leadRepository,
                            TicketRepository ticketRepository,
                            UserRepository userRepository,
                            CustomerReferralRepository referralRepository,
                            TicketFeedbackRepository feedbackRepository,
                            RequestUserService requestUserService) {
        this.leadRepository = leadRepository;
        this.ticketRepository = ticketRepository;
        this.userRepository = userRepository;
        this.referralRepository = referralRepository;
        this.feedbackRepository = feedbackRepository;
        this.requestUserService = requestUserService;
    }

    public Map<String, Object> overview(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "ADMIN", "SUPERVISOR");

        List<Lead> leads = leadRepository.findAll();
        List<Ticket> tickets = ticketRepository.findAll();
        List<User> users = userRepository.findAll();
        List<TicketFeedback> feedback = feedbackRepository.findAll();

        long resolved = tickets.stream().filter(this::isResolved).count();
        long openTickets = tickets.stream().filter(t -> !isResolved(t)).count();
        int resolutionRatePercent = tickets.isEmpty() ? 0 : (int) Math.round((resolved * 100.0) / tickets.size());

        long convertedLeads = leads.stream().filter(l -> "converted".equalsIgnoreCase(l.getStatus())).count();
        int leadConversionRate = leads.isEmpty() ? 0 : (int) Math.round((convertedLeads * 100.0) / leads.size());

        long totalCustomers = users.stream()
                .filter(User::isActive)
                .filter(u -> "CUSTOMER".equalsIgnoreCase(u.getRole()))
                .count();
        long totalAgents = users.stream()
                .filter(User::isActive)
                .filter(u -> List.of("SALES_AGENT", "SUPPORT_AGENT").contains(u.getRole()))
                .count();

        YearMonth currentMonth = YearMonth.now();
        long leadsThisMonth = leads.stream()
                .filter(l -> l.getCreatedAt() != null)
                .filter(l -> YearMonth.from(l.getCreatedAt().toLocalDate()).equals(currentMonth))
                .count();
        long ticketsThisMonth = tickets.stream()
                .filter(t -> t.getCreatedAt() != null)
                .filter(t -> YearMonth.from(t.getCreatedAt().toLocalDate()).equals(currentMonth))
                .count();

        double averageCsat = feedback.isEmpty()
                ? 0
                : feedback.stream().mapToInt(TicketFeedback::getRating).average().orElse(0);

        Map<String, Long> sourceCounts = new LinkedHashMap<>();
        for (Lead lead : leads) {
            String source = normalizeSource(lead.getSource());
            sourceCounts.merge(source, 1L, Long::sum);
        }
        for (var referral : referralRepository.findAll()) {
            if (referral.getHearAboutUs() != null && !referral.getHearAboutUs().isBlank()) {
                String source = normalizeSource(referral.getHearAboutUs());
                sourceCounts.merge(source, 1L, Long::sum);
            }
        }

        long totalSources = sourceCounts.values().stream().mapToLong(Long::longValue).sum();
        List<Map<String, Object>> leadSources = sourceCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> breakdownRow(e.getKey(), e.getValue(), totalSources))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketResolutionRate", resolutionRatePercent + "%");
        result.put("resolutionRatePercent", resolutionRatePercent);
        result.put("totalLeads", leads.size());
        result.put("totalTickets", tickets.size());
        result.put("openTickets", openTickets);
        result.put("resolvedTickets", resolved);
        result.put("totalCustomers", totalCustomers);
        result.put("totalAgents", totalAgents);
        result.put("leadConversionRate", leadConversionRate);
        result.put("convertedLeads", convertedLeads);
        result.put("leadsThisMonth", leadsThisMonth);
        result.put("ticketsThisMonth", ticketsThisMonth);
        result.put("averageCsat", Math.round(averageCsat * 10.0) / 10.0);
        result.put("feedbackCount", feedback.size());
        result.put("leadSources", leadSources);
        result.put("hearAboutUsBreakdown", hearAboutUsBreakdown());
        result.put("leadPipeline", leadPipeline(leads));
        result.put("ticketStatusBreakdown", ticketStatusBreakdown(tickets));
        result.put("ticketPriorityBreakdown", ticketPriorityBreakdown(tickets));
        result.put("ticketCategoryBreakdown", ticketCategoryBreakdown(tickets));
        result.put("teamPerformance", teamPerformanceBreakdown(users, leads, tickets));
        result.put("leadTrendMonthly", monthlyLeadTrend(leads));
        result.put("ticketTrendWeekly", weeklyTicketTrend(tickets));
        result.put("insights", buildInsights(
                leadSources, leadConversionRate, resolutionRatePercent, openTickets,
                leadsThisMonth, ticketsThisMonth, averageCsat, convertedLeads, leads.size()));
        return result;
    }

    public List<Map<String, Object>> agentPerformance(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "ADMIN", "SUPERVISOR");

        List<Ticket> tickets = ticketRepository.findAll();
        List<User> agents = userRepository.findAll().stream()
                .filter(u -> u.isActive() && u.getRole() != null)
                .filter(u -> List.of("SALES_AGENT", "SUPPORT_AGENT").contains(u.getRole().toUpperCase()))
                .toList();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (User agent : agents) {
            long ticketsResolved = tickets.stream()
                    .filter(t -> agent.getId().equals(t.getAssigneeId()))
                    .filter(this::isResolved)
                    .count();
            long openAssigned = tickets.stream()
                    .filter(t -> agent.getId().equals(t.getAssigneeId()))
                    .filter(t -> !isResolved(t))
                    .count();
            long leadsOwned = leadRepository.findByOwnerIdOrderByCreatedAtDesc(agent.getId()).size();
            long convertedLeads = leadRepository.findByOwnerIdOrderByCreatedAtDesc(agent.getId()).stream()
                    .filter(l -> "converted".equalsIgnoreCase(l.getStatus()))
                    .count();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", agent.getId());
            row.put("name", agent.getFullName());
            row.put("role", formatRole(agent.getRole()));
            row.put("ticketsResolved", ticketsResolved);
            row.put("openTickets", openAssigned);
            row.put("leadsOwned", leadsOwned);
            row.put("leadsConverted", convertedLeads);
            row.put("avgResponse", formatAvgResponse(agent, tickets));
            row.put("rating", agent.getAverageRating() > 0 ? agent.getAverageRating() : "—");
            rows.add(row);
        }
        rows.sort(Comparator
                .comparingLong((Map<String, Object> r) -> ((Number) r.get("ticketsResolved")).longValue()).reversed()
                .thenComparingLong(r -> ((Number) r.get("leadsConverted")).longValue()).reversed());
        return rows;
    }

    private List<Map<String, Object>> leadPipeline(List<Lead> leads) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String stage : LEAD_STAGES) {
            counts.put(stage, 0L);
        }
        for (Lead lead : leads) {
            String status = lead.getStatus() == null ? "new" : lead.getStatus().toLowerCase();
            if (!counts.containsKey(status)) {
                counts.put(status, 0L);
            }
            counts.merge(status, 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", e.getKey());
                    row.put("label", LEAD_STAGE_LABELS.getOrDefault(e.getKey(), capitalize(e.getKey())));
                    row.put("count", e.getValue());
                    return row;
                })
                .toList();
    }

    private List<Map<String, Object>> ticketStatusBreakdown(List<Ticket> tickets) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Ticket ticket : tickets) {
            String status = ticket.getStatus() == null ? "open" : ticket.getStatus().toLowerCase();
            counts.merge(status, 1L, Long::sum);
        }
        long total = tickets.size();
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> breakdownRow(e.getKey(), e.getValue(), total, TICKET_STATUS_LABELS))
                .toList();
    }

    private List<Map<String, Object>> ticketPriorityBreakdown(List<Ticket> tickets) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Ticket ticket : tickets) {
            String priority = ticket.getPriority() == null ? "medium" : ticket.getPriority().toLowerCase();
            counts.merge(priority, 1L, Long::sum);
        }
        long total = tickets.size();
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> breakdownRow(e.getKey(), e.getValue(), total, null))
                .toList();
    }

    private List<Map<String, Object>> ticketCategoryBreakdown(List<Ticket> tickets) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Ticket ticket : tickets) {
            String category = ticket.getCategory() == null || ticket.getCategory().isBlank()
                    ? "general"
                    : ticket.getCategory().toLowerCase();
            counts.merge(category, 1L, Long::sum);
        }
        long total = tickets.size();
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> breakdownRow(e.getKey(), e.getValue(), total, null))
                .toList();
    }

    private List<Map<String, Object>> teamPerformanceBreakdown(List<User> users, List<Lead> leads, List<Ticket> tickets) {
        List<Map<String, Object>> teams = new ArrayList<>();
        for (String roleKey : List.of("SALES_AGENT", "SUPPORT_AGENT")) {
            List<User> team = users.stream()
                    .filter(User::isActive)
                    .filter(u -> roleKey.equalsIgnoreCase(u.getRole()))
                    .toList();
            if (team.isEmpty()) {
                continue;
            }
            long ticketsResolved = 0;
            long openTickets = 0;
            long leadsOwned = 0;
            long leadsConverted = 0;
            for (User agent : team) {
                ticketsResolved += tickets.stream()
                        .filter(t -> agent.getId().equals(t.getAssigneeId()))
                        .filter(this::isResolved)
                        .count();
                openTickets += tickets.stream()
                        .filter(t -> agent.getId().equals(t.getAssigneeId()))
                        .filter(t -> !isResolved(t))
                        .count();
                List<Lead> owned = leadRepository.findByOwnerIdOrderByCreatedAtDesc(agent.getId());
                leadsOwned += owned.size();
                leadsConverted += owned.stream()
                        .filter(l -> "converted".equalsIgnoreCase(l.getStatus()))
                        .count();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("team", formatRole(roleKey));
            row.put("roleKey", roleKey);
            row.put("agents", team.size());
            row.put("ticketsResolved", ticketsResolved);
            row.put("openTickets", openTickets);
            row.put("leadsOwned", leadsOwned);
            row.put("leadsConverted", leadsConverted);
            teams.add(row);
        }
        return teams;
    }

    private List<String> buildInsights(List<Map<String, Object>> leadSources,
                                       int leadConversionRate,
                                       int resolutionRatePercent,
                                       long openTickets,
                                       long leadsThisMonth,
                                       long ticketsThisMonth,
                                       double averageCsat,
                                       long convertedLeads,
                                       int totalLeads) {
        List<String> insights = new ArrayList<>();
        if (!leadSources.isEmpty()) {
            Map<String, Object> top = leadSources.get(0);
            insights.add("Top acquisition channel is "
                    + top.get("source") + " at " + top.get("pct") + "% (" + top.get("count") + " leads).");
        }
        if (totalLeads > 0) {
            insights.add("Lead conversion rate is " + leadConversionRate + "% with "
                    + convertedLeads + " converted lead(s) in the pipeline.");
        }
        insights.add("Support resolution rate is " + resolutionRatePercent + "% with "
                + openTickets + " ticket(s) still open.");
        if (leadsThisMonth > 0 || ticketsThisMonth > 0) {
            insights.add("This month: " + leadsThisMonth + " new lead(s) and " + ticketsThisMonth + " new ticket(s).");
        }
        if (averageCsat > 0) {
            insights.add("Average customer satisfaction is " + Math.round(averageCsat * 10.0) / 10.0 + "/5 from recent feedback.");
        }
        return insights;
    }

    private List<Map<String, Object>> monthlyLeadTrend(List<Lead> leads) {
        LocalDate now = LocalDate.now();
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            YearMonth month = YearMonth.from(now.minusMonths(i));
            long count = leads.stream()
                    .filter(l -> l.getCreatedAt() != null)
                    .filter(l -> YearMonth.from(l.getCreatedAt().toLocalDate()).equals(month))
                    .count();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", month.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
            row.put("month", month.toString());
            row.put("count", count);
            trend.add(row);
        }
        return trend;
    }

    private List<Map<String, Object>> weeklyTicketTrend(List<Ticket> tickets) {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            long created = tickets.stream()
                    .filter(t -> t.getCreatedAt() != null)
                    .filter(t -> t.getCreatedAt().toLocalDate().equals(day))
                    .count();
            long resolved = tickets.stream()
                    .filter(this::isResolved)
                    .filter(t -> t.getUpdatedAt() != null)
                    .filter(t -> t.getUpdatedAt().toLocalDate().equals(day))
                    .count();
            long open = tickets.stream()
                    .filter(t -> !isResolved(t))
                    .filter(t -> t.getCreatedAt() != null)
                    .filter(t -> !t.getCreatedAt().toLocalDate().isAfter(day))
                    .count();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
            row.put("date", day.toString());
            row.put("total", created);
            row.put("resolved", resolved);
            row.put("open", open);
            trend.add(row);
        }
        return trend;
    }

    private List<Map<String, Object>> hearAboutUsBreakdown() {
        Map<String, Long> counts = new LinkedHashMap<>();
        referralRepository.findAll().forEach(ref -> {
            if (ref.getHearAboutUs() != null && !ref.getHearAboutUs().isBlank()) {
                String key = normalizeSource(ref.getHearAboutUs());
                counts.merge(key, 1L, Long::sum);
            }
        });
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> breakdownRow(e.getKey(), e.getValue(), total))
                .toList();
    }

    private Map<String, Object> breakdownRow(String key, long count, long total) {
        return breakdownRow(key, count, total, SOURCE_LABELS);
    }

    private Map<String, Object> breakdownRow(String key, long count, long total, Map<String, String> labels) {
        int pct = total == 0 ? 0 : (int) Math.round((count * 100.0) / total);
        Map<String, Object> row = new LinkedHashMap<>();
        String label = labels != null
                ? labels.getOrDefault(key, capitalize(key))
                : capitalize(key);
        row.put("source", label);
        row.put("key", key);
        row.put("count", count);
        row.put("pct", pct);
        return row;
    }

    private boolean isResolved(Ticket ticket) {
        String status = ticket.getStatus();
        return status != null && ("resolved".equalsIgnoreCase(status) || "closed".equalsIgnoreCase(status));
    }

    private String formatAvgResponse(User agent, List<Ticket> tickets) {
        OptionalDouble minutes = tickets.stream()
                .filter(t -> agent.getId().equals(t.getAssigneeId()))
                .filter(this::isResolved)
                .filter(t -> t.getCreatedAt() != null && t.getUpdatedAt() != null)
                .mapToLong(t -> ChronoUnit.MINUTES.between(t.getCreatedAt(), t.getUpdatedAt()))
                .average();
        if (minutes.isEmpty()) {
            return "—";
        }
        long totalMinutes = Math.max(1, Math.round(minutes.getAsDouble()));
        if (totalMinutes < 60) {
            return totalMinutes + "m";
        }
        long hours = totalMinutes / 60;
        long remainder = totalMinutes % 60;
        return remainder > 0 ? hours + "h " + remainder + "m" : hours + "h";
    }

    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "website";
        }
        String normalized = source.trim().toLowerCase().replace(' ', '_');
        if (normalized.contains("linkedin")) {
            return "linkedin";
        }
        if (normalized.contains("refer")) {
            return "referral";
        }
        if (normalized.contains("event")) {
            return "event";
        }
        if (normalized.contains("website") || normalized.contains("google") || normalized.contains("search")) {
            return "website";
        }
        return normalized;
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "Other";
        }
        return Arrays.stream(value.split("_"))
                .map(part -> part.substring(0, 1).toUpperCase() + part.substring(1))
                .collect(Collectors.joining(" "));
    }

    private String formatRole(String role) {
        if (role == null) {
            return "Staff";
        }
        return switch (role.toUpperCase()) {
            case "SALES_AGENT" -> "Sales Agent";
            case "SUPPORT_AGENT" -> "Support Agent";
            default -> role;
        };
    }
}
