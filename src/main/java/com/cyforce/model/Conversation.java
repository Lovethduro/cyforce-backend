package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "conversations")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Conversation {
    @Id
    private String id;
    private String customerId;
    private String customerName;
    private String customerEmail;
    private String leadId;
    private String guestAccessToken;
    private LocalDateTime guestTokenExpiresAt;
    private String salesAgentId;
    private String salesAgentName;
    private String salesAgentAvatarUrl;
    private String subject;
    private String status;
    private String ticketId;
    private String supervisorId;
    private String supervisorName;
    private String linkedInvoiceId;
    private Long agreedAmountKobo;
    private LocalDateTime forwardedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime closedAt;
    private String closeReason;
    private Integer customerRating;
    private String ratingComment;
    private LocalDateTime ratedAt;
}
