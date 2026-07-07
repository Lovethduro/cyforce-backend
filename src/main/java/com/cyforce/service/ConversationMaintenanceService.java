package com.cyforce.service;

import com.cyforce.model.Conversation;
import com.cyforce.repository.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ConversationMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMaintenanceService.class);

    private final ConversationRepository conversationRepository;
    private final NotificationService notificationService;

    public ConversationMaintenanceService(ConversationRepository conversationRepository,
                                            NotificationService notificationService) {
        this.conversationRepository = conversationRepository;
        this.notificationService = notificationService;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void closeInactiveConversations() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(1);
        List<String> activeStatuses = List.of("open", "invoice_sent", "forwarded");
        List<Conversation> stale = conversationRepository
                .findByStatusInAndUpdatedAtBefore(activeStatuses, cutoff);

        int closed = 0;
        for (Conversation conversation : stale) {
            conversation.setStatus("pending_rating");
            conversation.setClosedAt(LocalDateTime.now());
            conversation.setCloseReason("inactivity");
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationRepository.save(conversation);

            if (conversation.getCustomerId() != null) {
                notificationService.create(conversation.getCustomerId(), "Conversation closed",
                        "Your chat \"" + conversation.getSubject() + "\" was closed due to inactivity. Please rate your experience.",
                        "info");
            }
            closed++;
        }

        if (closed > 0) {
            log.info("Moved {} inactive conversations to pending_rating", closed);
        }
    }
}
