package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "system_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SystemSettings {
    @Id
    private String id;
    private String appName;
    private String supportEmail;
    private String supportPhone;
    private String liveChatHours;
    private String slaUrgent;
    private String slaHigh;
    private String slaMedium;
    private String slaLow;
    private int sessionTimeoutMinutes;
    private int maxLoginAttempts;
    private boolean maintenanceMode;
    private boolean emailNotifications;
    private int dataRetentionDays = 90;
    private LocalDateTime lastBackupAt;
    private String lastBackupSummary;
    private Long lastBackupSizeBytes;
    private LocalDateTime updatedAt;
}
