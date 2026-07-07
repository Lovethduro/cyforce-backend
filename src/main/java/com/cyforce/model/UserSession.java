package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "user_sessions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserSession {
    @Id
    private String id;

    @Indexed(unique = true)
    private String sessionId;

    @Indexed
    private String userId;

    private String userEmail;
    private String fullName;
    private String role;
    private String clientIp;
    private String userAgent;
    private LocalDateTime startedAt;
    private LocalDateTime lastActivityAt;
    private LocalDateTime endedAt;
    private boolean active;
}
