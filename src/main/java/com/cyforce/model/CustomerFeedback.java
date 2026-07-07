package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

@Document(collection = "customer_feedback")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerFeedback {
    @Id
    private String id;
    /** CONVERSATION, TICKET, PURCHASE */
    private String type;
    private String referenceId;
    private String customerId;
    private String customerName;
    private String agentId;
    private String agentName;
    private String agentRole;
    private int rating;
    private String comment;
    private Map<String, Object> questionnaire;
    private LocalDateTime createdAt;
}
