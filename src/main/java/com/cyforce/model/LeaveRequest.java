package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Document(collection = "leave_requests")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaveRequest {
    @Id
    private String id;
    private String userId;
    private String userName;
    private LocalDate startDate;
    private LocalDate endDate;
    private String reason;
    private int daysRequested;
    /** pending, approved, rejected */
    private String status;
    private String reviewedByUserId;
    private String reviewedByName;
    private String reviewNote;
    private String calendarEventId;
    private LocalDateTime createdAt;
    private LocalDateTime reviewedAt;
}
