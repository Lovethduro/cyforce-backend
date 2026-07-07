package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "calendar_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CalendarEvent {
    @Id
    private String id;
    private String title;
    private String description;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    /** company, personal, leave */
    private String eventType;
    private String createdByUserId;
    private String createdByName;
    private List<String> taggedUserIds = new ArrayList<>();
    /** Roles notified / able to see company events when set (e.g. SALES_AGENT). */
    private List<String> targetRoles = new ArrayList<>();
    /** Users who should see this event on their calendar (notified staff). */
    private List<String> notifiedUserIds = new ArrayList<>();
    private boolean reminderSent;
    private LocalDateTime createdAt;
}
