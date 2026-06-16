package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "ticket_messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TicketMessage {
    @Id
    private String id;
    private String ticketId;
    private String authorId;
    private String authorName;
    private String authorAvatarUrl;
    private String message;
    private boolean internalNote;
    private LocalDateTime createdAt;
}
