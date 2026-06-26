package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "tickets")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Ticket {
    @Id
    private String id;
    private String customerId;
    private String customerName;
    private String customerEmail;
    private String subject;
    private String description;
    private String attachmentUrl;
    private String category;
    private String priority;
    private String status;
    private String assigneeId;
    private String assigneeName;
    private String assigneeAvatarUrl;
    private String salesConversationId;
    private boolean transferredToSales = false;
    private LocalDateTime transferredAt;
    // Used for automatic SLA escalation notifications to avoid duplicate alerts.
    private boolean slaEscalated = false;
    private LocalDateTime slaEscalatedAt;
    private boolean adminTakeover = false;
    private LocalDateTime adminTakeoverAt;
    private String adminTakeoverById;
    private String guestAccessToken;
    private LocalDateTime guestTokenExpiresAt;
    private String mergedIntoTicketId;
    private LocalDateTime mergedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
