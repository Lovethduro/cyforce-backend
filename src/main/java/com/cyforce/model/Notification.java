package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "notifications")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Notification {
    @Id
    private String id;
    private String userId;
    private String title;
    private String message;
    private String type;
    private boolean read;
    /** Dedupes notifications for the same event (e.g. invoiceId:purchase). */
    private String referenceId;
    /** When set, the client can show an inline purchase survey instead of a link. */
    private String surveyToken;
    private LocalDateTime createdAt;
}
