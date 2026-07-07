package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Document(collection = "approval_requests")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalRequest {
    @Id
    private String id;
    /** lead_assignment, leave, user_registration, customer_agent_request */
    private String type;
    private String requestedByUserId;
    private String requestedByName;
    private Map<String, Object> payload = new HashMap<>();
    private String status;
    private String proofUrl;
    private String emergencyReason;
    private String reviewedByUserId;
    private String reviewedByName;
    private String reviewNote;
    private LocalDateTime createdAt;
    private LocalDateTime reviewedAt;
}
