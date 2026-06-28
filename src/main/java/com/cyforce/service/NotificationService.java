package com.cyforce.service;

import com.cyforce.model.Notification;
import com.cyforce.model.User;
import com.cyforce.repository.InvoiceRepository;
import com.cyforce.repository.NotificationRepository;
import com.cyforce.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final InvoiceRepository invoiceRepository;
    private final RequestUserService requestUserService;
    private final UserRepository userRepository;

    public NotificationService(NotificationRepository notificationRepository,
                               InvoiceRepository invoiceRepository,
                               RequestUserService requestUserService,
                               UserRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.invoiceRepository = invoiceRepository;
        this.requestUserService = requestUserService;
        this.userRepository = userRepository;
    }

    public List<Notification> listForUser(String userId) {
        requestUserService.requireUser(userId);
        List<Notification> notifications = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId);
        for (Notification notification : notifications) {
            clearSurveyTokenIfCompleted(notification);
        }
        return notifications;
    }

    public void clearPurchaseSurveyPrompt(String userId, String invoiceId) {
        if (userId == null || userId.isBlank() || invoiceId == null || invoiceId.isBlank()) {
            return;
        }
        notificationRepository.findByUserIdAndReferenceId(userId, invoiceId + ":purchase")
                .ifPresent(this::clearSurveyTokenIfPresent);
    }

    public long unreadCount(String userId) {
        return notificationRepository.countByUserIdAndReadFalse(userId);
    }

    public Notification markRead(String userId, String notificationId) {
        Notification notification = getOwnedNotification(userId, notificationId);
        notification.setRead(true);
        return notificationRepository.save(notification);
    }

    public void markAllRead(String userId) {
        List<Notification> notifications = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId);
        for (Notification n : notifications) {
            if (!n.isRead()) {
                n.setRead(true);
                notificationRepository.save(n);
            }
        }
    }

    public void delete(String userId, String notificationId) {
        Notification notification = getOwnedNotification(userId, notificationId);
        notificationRepository.delete(notification);
    }

    public void deleteAll(String userId) {
        requestUserService.requireUser(userId);
        notificationRepository.deleteByUserId(userId);
    }

    public int broadcastToAll(String title, String message) {
        return broadcastToAudience(title, message, "all");
    }

    public int broadcastToAudience(String title, String message, String audience) {
        int count = 0;
        for (User user : userRepository.findAll()) {
            if (matchesAudience(user, audience)) {
                create(user.getId(), title, message, "info");
                count++;
            }
        }
        return count;
    }

    private boolean matchesAudience(User user, String audience) {
        if (audience == null || audience.isBlank() || "all".equalsIgnoreCase(audience)) {
            return true;
        }
        String role = user.getRole() == null ? "" : user.getRole().toUpperCase();
        return switch (audience.toLowerCase().replace(" ", "_")) {
            case "admins", "admins_only", "admin" -> role.equals("ADMIN");
            case "sales", "sales_team" -> role.equals("SALES_AGENT");
            case "support", "support_team" -> role.equals("SUPPORT_AGENT");
            case "customers", "customer" -> role.equals("CUSTOMER");
            case "supervisors" -> role.equals("SUPERVISOR");
            default -> true;
        };
    }

    public Notification create(String userId, String title, String message, String type) {
        return create(userId, title, message, type, null, null);
    }

    public Notification createOnce(String userId, String referenceId, String title, String message, String type) {
        return create(userId, title, message, type, referenceId, null);
    }

    public Notification createOnce(String userId,
                                   String referenceId,
                                   String title,
                                   String message,
                                   String type,
                                   String surveyToken) {
        return create(userId, title, message, type, referenceId, surveyToken);
    }

    private Notification create(String userId,
                              String title,
                              String message,
                              String type,
                              String referenceId,
                              String surveyToken) {
        if (referenceId != null && !referenceId.isBlank()) {
            Optional<Notification> existing = notificationRepository.findByUserIdAndReferenceId(userId, referenceId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setType(type == null ? "info" : type);
        notification.setReferenceId(referenceId);
        notification.setSurveyToken(surveyToken);
        notification.setRead(false);
        notification.setCreatedAt(LocalDateTime.now());
        return notificationRepository.save(notification);
    }

    private void clearSurveyTokenIfCompleted(Notification notification) {
        String surveyToken = notification.getSurveyToken();
        if (surveyToken == null || surveyToken.isBlank()) {
            return;
        }
        invoiceRepository.findBySurveyToken(surveyToken).ifPresent(invoice -> {
            if (invoice.isSurveyCompleted()) {
                clearSurveyTokenIfPresent(notification);
            }
        });
    }

    private void clearSurveyTokenIfPresent(Notification notification) {
        if (notification.getSurveyToken() == null) {
            return;
        }
        notification.setSurveyToken(null);
        notificationRepository.save(notification);
    }

    private Notification getOwnedNotification(String userId, String notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new RuntimeException("Notification not found"));
        if (!userId.equals(notification.getUserId())) {
            throw new RuntimeException("Notification not found");
        }
        return notification;
    }
}
