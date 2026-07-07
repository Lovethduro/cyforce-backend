package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Document(collection = "motivational_messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MotivationalMessage {
    @Id
    private String id;
    private String message;
    private List<String> roles;
    private boolean active = true;
    private String createdById;
    private String createdByName;
    private LocalDateTime createdAt;
}
