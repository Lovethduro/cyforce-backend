package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "ticket_feedback")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TicketFeedback {
    @Id
    private String id;
    private String ticketId;
    private String customerId;
    private String customerName;
    private String companyName;
    private int rating;
    private String comment;
    private String assigneeId;
    private LocalDateTime createdAt;
}
