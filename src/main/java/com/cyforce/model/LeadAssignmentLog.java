package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "lead_assignment_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeadAssignmentLog {
    @Id
    private String id;
    private String leadId;
    private String agentId;
    private String agentName;
    private String assignedByUserId;
    private String assignmentType;
    private LocalDateTime assignedAt;
}
