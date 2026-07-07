package com.cyforce.service;

import com.cyforce.model.SystemSettings;
import com.cyforce.model.User;
import com.cyforce.repository.SystemSettingsRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SystemConfigService {

    public static final String GLOBAL_ID = "global";

    private final SystemSettingsRepository settingsRepository;
    private final RequestUserService requestUserService;

    public SystemConfigService(SystemSettingsRepository settingsRepository,
                               RequestUserService requestUserService) {
        this.settingsRepository = settingsRepository;
        this.requestUserService = requestUserService;
    }

    public Map<String, Object> publicSupportConfig() {
        SystemSettings settings = getOrCreateDefaults();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("supportEmail", settings.getSupportEmail());
        config.put("supportPhone", settings.getSupportPhone());
        config.put("liveChatHours", settings.getLiveChatHours());
        config.put("responseSla", Map.of(
                "urgent", settings.getSlaUrgent(),
                "high", settings.getSlaHigh(),
                "medium", settings.getSlaMedium(),
                "low", settings.getSlaLow()
        ));
        return config;
    }

    public SystemSettings getAdminConfig(String userId) {
        requestUserService.requireRole(requestUserService.requireUser(userId), "ADMIN");
        return getOrCreateDefaults();
    }

    public Map<String, Object> getAdminConfigView(String userId) {
        return toAdminView(getAdminConfig(userId));
    }

    public Map<String, Object> toAdminView(SystemSettings settings) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("appName", settings.getAppName());
        view.put("supportEmail", settings.getSupportEmail());
        view.put("supportPhone", settings.getSupportPhone());
        view.put("liveChatHours", settings.getLiveChatHours());
        view.put("slaUrgent", settings.getSlaUrgent());
        view.put("slaHigh", settings.getSlaHigh());
        view.put("slaMedium", settings.getSlaMedium());
        view.put("slaLow", settings.getSlaLow());
        view.put("sessionTimeoutMinutes", settings.getSessionTimeoutMinutes());
        view.put("maxLoginAttempts", settings.getMaxLoginAttempts());
        view.put("maintenanceMode", settings.isMaintenanceMode());
        view.put("emailNotifications", settings.isEmailNotifications());
        view.put("dataRetentionDays", settings.getDataRetentionDays());
        return view;
    }

    public SystemSettings updateAdminConfig(String userId, Map<String, Object> body) {
        User admin = requestUserService.requireUser(userId);
        requestUserService.requireRole(admin, "ADMIN");

        SystemSettings settings = getOrCreateDefaults();
        if (body.get("appName") != null) settings.setAppName(body.get("appName").toString().trim());
        if (body.get("supportEmail") != null) settings.setSupportEmail(body.get("supportEmail").toString().trim());
        if (body.get("supportPhone") != null) settings.setSupportPhone(body.get("supportPhone").toString().trim());
        if (body.get("liveChatHours") != null) settings.setLiveChatHours(body.get("liveChatHours").toString().trim());
        if (body.get("slaUrgent") != null) settings.setSlaUrgent(body.get("slaUrgent").toString().trim());
        if (body.get("slaHigh") != null) settings.setSlaHigh(body.get("slaHigh").toString().trim());
        if (body.get("slaMedium") != null) settings.setSlaMedium(body.get("slaMedium").toString().trim());
        if (body.get("slaLow") != null) settings.setSlaLow(body.get("slaLow").toString().trim());
        if (body.get("sessionTimeout") != null) {
            settings.setSessionTimeoutMinutes(parseInt(body.get("sessionTimeout"), settings.getSessionTimeoutMinutes()));
        }
        if (body.get("sessionTimeoutMinutes") != null) {
            settings.setSessionTimeoutMinutes(parseInt(body.get("sessionTimeoutMinutes"), settings.getSessionTimeoutMinutes()));
        }
        if (body.get("maxLoginAttempts") != null) {
            settings.setMaxLoginAttempts(parseInt(body.get("maxLoginAttempts"), settings.getMaxLoginAttempts()));
        }
        if (body.containsKey("maintenanceMode")) {
            settings.setMaintenanceMode(Boolean.TRUE.equals(body.get("maintenanceMode")));
        }
        if (body.containsKey("emailNotifications")) {
            settings.setEmailNotifications(Boolean.TRUE.equals(body.get("emailNotifications")));
        }
        if (body.get("dataRetentionDays") != null) {
            settings.setDataRetentionDays(Math.max(7, Math.min(365, parseInt(body.get("dataRetentionDays"), settings.getDataRetentionDays()))));
        }
        settings.setUpdatedAt(LocalDateTime.now());
        return settingsRepository.save(settings);
    }

    public SystemSettings getOrCreateDefaults() {
        return settingsRepository.findById(GLOBAL_ID).orElseGet(() -> {
            SystemSettings defaults = defaultSettings();
            return settingsRepository.save(defaults);
        });
    }

    public void seedDefaultsIfMissing() {
        if (!settingsRepository.existsById(GLOBAL_ID)) {
            settingsRepository.save(defaultSettings());
        }
    }

    private SystemSettings defaultSettings() {
        SystemSettings settings = new SystemSettings();
        settings.setId(GLOBAL_ID);
        settings.setAppName("CyForce CRM");
        settings.setSupportEmail("support@cyforce.com");
        settings.setSupportPhone("+234 800 CYFORCE");
        settings.setLiveChatHours("Available 9 AM – 6 PM (WAT)");
        settings.setSlaUrgent("2–4 hours");
        settings.setSlaHigh("8–12 hours");
        settings.setSlaMedium("24 hours");
        settings.setSlaLow("48 hours");
        settings.setSessionTimeoutMinutes(30);
        settings.setMaxLoginAttempts(5);
        settings.setMaintenanceMode(false);
        settings.setEmailNotifications(true);
        settings.setDataRetentionDays(90);
        settings.setUpdatedAt(LocalDateTime.now());
        return settings;
    }

    private int parseInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
