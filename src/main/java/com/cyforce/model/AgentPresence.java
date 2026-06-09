package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "agent_presence")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentPresence {
    @Id
    private String id;
    private String userId;
    private String fullName;
    private String role;
    private String team;
    private String status;
    private LocalDateTime statusSince;
    private String shiftLabel;
    private LocalDateTime updatedAt;
}
