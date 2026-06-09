package com.cyforce.service;

import com.cyforce.model.Ticket;
import com.cyforce.model.TicketMessage;
import com.cyforce.repository.TicketMessageRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
public class TicketMetricsService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final TicketMessageRepository messageRepository;

    public TicketMetricsService(TicketMessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    public int slaHoursForPriority(String priority) {
        if (priority == null) return 8;
        return switch (priority.toLowerCase()) {
            case "high", "urgent", "critical" -> 4;
            case "low" -> 24;
            default -> 8;
        };
    }

    public LocalDateTime slaDeadline(Ticket ticket) {
        if (ticket.getCreatedAt() == null) return LocalDateTime.now().plusHours(8);
        return ticket.getCreatedAt().plusHours(slaHoursForPriority(ticket.getPriority()));
    }

    public boolean isSlaBreached(Ticket ticket) {
        if ("resolved".equals(ticket.getStatus()) || "closed".equals(ticket.getStatus())) {
            LocalDateTime resolvedAt = ticket.getUpdatedAt() != null ? ticket.getUpdatedAt() : LocalDateTime.now();
            return resolvedAt.isAfter(slaDeadline(ticket));
        }
        return LocalDateTime.now().isAfter(slaDeadline(ticket));
    }

    public int slaProgressPercent(Ticket ticket) {
        if (ticket.getCreatedAt() == null) return 0;
        long totalMinutes = Duration.between(ticket.getCreatedAt(), slaDeadline(ticket)).toMinutes();
        if (totalMinutes <= 0) return 100;
        long elapsed = Duration.between(ticket.getCreatedAt(), LocalDateTime.now()).toMinutes();
        return (int) Math.min(100, Math.round((elapsed * 100.0) / totalMinutes));
    }

    public String slaRemainingLabel(Ticket ticket) {
        LocalDateTime deadline = slaDeadline(ticket);
        Duration remaining = Duration.between(LocalDateTime.now(), deadline);
        if (remaining.isNegative()) {
            long overdue = remaining.abs().toMinutes();
            if (overdue < 60) return overdue + "m overdue";
            return (overdue / 60) + "h overdue";
        }
        long mins = remaining.toMinutes();
        if (mins < 60) return mins + "m left";
        return (mins / 60) + "h " + (mins % 60) + "m left";
    }

    public double avgResponseHours(List<Ticket> tickets, String assigneeId) {
        if (tickets.isEmpty()) return 0;
        double totalHours = 0;
        int counted = 0;
        for (Ticket ticket : tickets) {
            Double hours = firstResponseHours(ticket, assigneeId);
            if (hours != null) {
                totalHours += hours;
                counted++;
            }
        }
        if (counted == 0) return 0;
        return Math.round((totalHours / counted) * 10.0) / 10.0;
    }

    public Double firstResponseHours(Ticket ticket, String assigneeId) {
        if (ticket.getCreatedAt() == null) return null;
        List<TicketMessage> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId());
        for (TicketMessage msg : messages) {
            if (msg.isInternalNote()) continue;
            if (assigneeId != null && !assigneeId.equals(msg.getAuthorId())) continue;
            if (msg.getCreatedAt() == null) continue;
            double hours = Duration.between(ticket.getCreatedAt(), msg.getCreatedAt()).toMinutes() / 60.0;
            return Math.max(0, hours);
        }
        return null;
    }

    public double avgResolutionHours(List<Ticket> tickets) {
        List<Ticket> resolved = tickets.stream()
                .filter(t -> "resolved".equals(t.getStatus()) || "closed".equals(t.getStatus()))
                .filter(t -> t.getCreatedAt() != null && t.getUpdatedAt() != null)
                .toList();
        if (resolved.isEmpty()) return 0;
        double total = resolved.stream()
                .mapToDouble(t -> Duration.between(t.getCreatedAt(), t.getUpdatedAt()).toMinutes() / 60.0)
                .sum();
        return Math.round((total / resolved.size()) * 10.0) / 10.0;
    }

    public int slaCompliancePercent(List<Ticket> tickets) {
        List<Ticket> closed = tickets.stream()
                .filter(t -> "resolved".equals(t.getStatus()) || "closed".equals(t.getStatus()))
                .toList();
        if (closed.isEmpty()) return 100;
        long compliant = closed.stream().filter(t -> !isSlaBreached(t)).count();
        return (int) Math.round((compliant * 100.0) / closed.size());
    }

    public String formatHours(double hours) {
        if (hours <= 0) return "—";
        if (hours < 1) return Math.round(hours * 60) + "m";
        return hours + "h";
    }

    public String formatRelative(LocalDateTime dateTime) {
        if (dateTime == null) return "";
        Duration diff = Duration.between(dateTime, LocalDateTime.now());
        long mins = diff.toMinutes();
        if (mins < 1) return "just now";
        if (mins < 60) return mins + "m ago";
        long hours = mins / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    public String formatIso(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.format(ISO);
    }

    public String ticketNumber(Ticket ticket) {
        if (ticket.getId() == null) return "#0000";
        String suffix = ticket.getId().length() > 6
                ? ticket.getId().substring(ticket.getId().length() - 6).toUpperCase(Locale.ENGLISH)
                : ticket.getId().toUpperCase(Locale.ENGLISH);
        return "#" + suffix;
    }
}
