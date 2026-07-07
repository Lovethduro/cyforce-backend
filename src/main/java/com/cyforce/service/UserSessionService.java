package com.cyforce.service;

import com.cyforce.model.AgentPresence;
import com.cyforce.model.User;
import com.cyforce.model.UserSession;
import com.cyforce.repository.AgentPresenceRepository;
import com.cyforce.repository.UserSessionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserSessionService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final UserSessionRepository sessionRepository;
    private final AgentPresenceRepository presenceRepository;
    private final RequestUserService requestUserService;
    private final AuditLogService auditLogService;

    public UserSessionService(UserSessionRepository sessionRepository,
                              AgentPresenceRepository presenceRepository,
                              RequestUserService requestUserService,
                              AuditLogService auditLogService) {
        this.sessionRepository = sessionRepository;
        this.presenceRepository = presenceRepository;
        this.requestUserService = requestUserService;
        this.auditLogService = auditLogService;
    }

    public String startSession(User user, String clientIp, String userAgent) {
        LocalDateTime now = LocalDateTime.now();
        UserSession session = new UserSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(user.getId());
        session.setUserEmail(user.getEmail());
        session.setFullName(user.getFullName());
        session.setRole(user.getRole());
        session.setClientIp(clientIp);
        session.setUserAgent(trimUserAgent(userAgent));
        session.setStartedAt(now);
        session.setLastActivityAt(now);
        session.setActive(true);
        sessionRepository.save(session);
        markAvailable(user);
        return session.getSessionId();
    }

    public void endSession(String userId, String sessionId, String clientIp) {
        User user = requestUserService.requireUser(userId);
        boolean ended = false;

        if (sessionId != null && !sessionId.isBlank()) {
            ended = sessionRepository.findBySessionId(sessionId)
                    .filter(session -> userId.equals(session.getUserId()) && session.isActive())
                    .map(session -> closeSession(session, now()))
                    .isPresent();
        }

        if (!ended) {
            sessionRepository.findByUserIdAndActiveTrue(userId).forEach(session -> closeSession(session, now()));
        }

        if (sessionRepository.countByUserIdAndActiveTrue(userId) == 0) {
            markUnavailable(user.getId());
        }

        auditLogService.logSecurity(
                user.getEmail(),
                "LOGOUT",
                "Authentication",
                "Signed out",
                user.getId(),
                clientIp
        );
    }

    public List<Map<String, Object>> listSessionsForAdmin(String adminId) {
        requestUserService.requireRole(requestUserService.requireUser(adminId), "ADMIN");
        return sessionRepository.findTop200ByOrderByStartedAtDesc().stream()
                .map(this::toSessionItem)
                .toList();
    }

    public long countActiveSessions() {
        return sessionRepository.countByActiveTrue();
    }

    private Map<String, Object> toSessionItem(UserSession session) {
        String availability = resolveAvailability(session.getUserId());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", session.getId());
        item.put("sessionId", session.getSessionId());
        item.put("userId", session.getUserId());
        item.put("userEmail", session.getUserEmail());
        item.put("fullName", session.getFullName());
        item.put("role", session.getRole());
        item.put("clientIp", session.getClientIp());
        item.put("userAgent", session.getUserAgent());
        item.put("startedAt", formatIso(session.getStartedAt()));
        item.put("lastActivityAt", formatIso(session.getLastActivityAt()));
        item.put("endedAt", formatIso(session.getEndedAt()));
        item.put("active", session.isActive());
        item.put("availability", availability);
        return item;
    }

    private String resolveAvailability(String userId) {
        return presenceRepository.findByUserId(userId)
                .map(AgentPresence::getStatus)
                .orElse(sessionRepository.countByUserIdAndActiveTrue(userId) > 0 ? "online" : "unavailable");
    }

    private UserSession closeSession(UserSession session, LocalDateTime endedAt) {
        session.setActive(false);
        session.setEndedAt(endedAt);
        session.setLastActivityAt(endedAt);
        return sessionRepository.save(session);
    }

    private void markAvailable(User user) {
        AgentPresence presence = presenceRepository.findByUserId(user.getId()).orElseGet(AgentPresence::new);
        presence.setUserId(user.getId());
        presence.setFullName(user.getFullName());
        presence.setRole(user.getRole());
        presence.setTeam(resolveTeam(user.getRole()));
        presence.setStatus(loginStatusForRole(user.getRole()));
        presence.setStatusSince(LocalDateTime.now());
        if (presence.getShiftLabel() == null || presence.getShiftLabel().isBlank()) {
            presence.setShiftLabel(defaultShiftLabel(user.getRole()));
        }
        presence.setUpdatedAt(LocalDateTime.now());
        presenceRepository.save(presence);
    }

    private void markUnavailable(String userId) {
        presenceRepository.findByUserId(userId).ifPresent(presence -> {
            presence.setStatus("unavailable");
            presence.setStatusSince(LocalDateTime.now());
            presence.setUpdatedAt(LocalDateTime.now());
            presenceRepository.save(presence);
        });
    }

    private String loginStatusForRole(String role) {
        if (role == null) {
            return "available";
        }
        return "CUSTOMER".equalsIgnoreCase(role) ? "online" : "available";
    }

    private String resolveTeam(String role) {
        if (role == null) {
            return "general";
        }
        return switch (role.toUpperCase()) {
            case "SALES_AGENT" -> "sales";
            case "SUPPORT_AGENT", "SUPERVISOR" -> "support";
            case "ADMIN" -> "admin";
            case "CUSTOMER" -> "customer";
            default -> "general";
        };
    }

    private String defaultShiftLabel(String role) {
        if (role != null && "CUSTOMER".equalsIgnoreCase(role)) {
            return "Customer session";
        }
        return "Standard shift · 8:00 AM – 4:00 PM";
    }

    private String trimUserAgent(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        String trimmed = userAgent.trim();
        return trimmed.length() > 240 ? trimmed.substring(0, 240) : trimmed;
    }

    private String formatIso(LocalDateTime value) {
        return value == null ? null : value.format(ISO);
    }

    private LocalDateTime now() {
        return LocalDateTime.now();
    }
}
