package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "conversation_messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessage {
    @Id
    private String id;
    private String conversationId;
    private String authorId;
    private String authorName;
    private String authorRole;
    private String message;
    private String attachmentUrl;
    private LocalDateTime createdAt;
}
