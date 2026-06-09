package com.cyforce.service;

import com.cyforce.model.Conversation;
import com.cyforce.model.ConversationMessage;
import com.cyforce.model.User;
import com.cyforce.repository.ConversationMessageRepository;
import com.cyforce.repository.ConversationRepository;
import com.cyforce.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class MessagingService {

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final RequestUserService requestUserService;
    private final NotificationService notificationService;

    public MessagingService(ConversationRepository conversationRepository,
                            ConversationMessageRepository messageRepository,
                            UserRepository userRepository,
                            RequestUserService requestUserService,
                            NotificationService notificationService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.requestUserService = requestUserService;
        this.notificationService = notificationService;
    }

    public List<Conversation> customerConversations(String userId) {
        User customer = requestUserService.requireUser(userId);
        requestUserService.requireRole(customer, "CUSTOMER");
        return conversationRepository.findByCustomerIdOrderByUpdatedAtDesc(customer.getId());
    }

    public List<Conversation> salesConversations(String userId) {
        User agent = requestUserService.requireUser(userId);
        requestUserService.requireRole(agent, "SALES_AGENT", "ADMIN", "SUPERVISOR");
        return conversationRepository.findBySalesAgentIdOrderByUpdatedAtDesc(agent.getId());
    }

    public Conversation startConversation(String userId, String subject, String message) {
        User customer = requestUserService.requireUser(userId);
        requestUserService.requireRole(customer, "CUSTOMER");

        User salesAgent = userRepository.findAll().stream()
                .filter(u -> "SALES_AGENT".equalsIgnoreCase(u.getRole()))
                .findFirst()
                .orElse(null);

        Conversation conversation = new Conversation();
        conversation.setCustomerId(customer.getId());
        conversation.setCustomerName(customer.getFullName());
        conversation.setCustomerEmail(customer.getEmail());
        if (salesAgent != null) {
            conversation.setSalesAgentId(salesAgent.getId());
            conversation.setSalesAgentName(salesAgent.getFullName());
        }
        conversation.setSubject(subject != null && !subject.isBlank() ? subject.trim() : "Sales inquiry");
        conversation.setStatus("open");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        Conversation saved = conversationRepository.save(conversation);

        if (message != null && !message.isBlank()) {
            sendMessage(userId, saved.getId(), message, null);
        }

        if (salesAgent != null) {
            notificationService.create(salesAgent.getId(), "New customer message",
                    customer.getFullName() + ": " + conversation.getSubject(), "info");
        }

        return conversationRepository.findById(saved.getId()).orElse(saved);
    }

    public List<ConversationMessage> getMessages(String userId, String conversationId) {
        Conversation conversation = requireAccess(userId, conversationId);
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
    }

    public ConversationMessage sendMessage(String userId, String conversationId, String message, String attachmentUrl) {
        if (message == null || message.isBlank()) {
            throw new RuntimeException("Message is required");
        }
        User user = requestUserService.requireUser(userId);
        Conversation conversation = requireAccess(userId, conversationId);

        ConversationMessage entry = new ConversationMessage();
        entry.setConversationId(conversation.getId());
        entry.setAuthorId(user.getId());
        entry.setAuthorName(user.getFullName());
        entry.setAuthorRole(user.getRole());
        entry.setMessage(message.trim());
        entry.setAttachmentUrl(attachmentUrl);
        entry.setCreatedAt(LocalDateTime.now());
        ConversationMessage saved = messageRepository.save(entry);

        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);

        if (user.getId().equals(conversation.getCustomerId()) && conversation.getSalesAgentId() != null) {
            notificationService.create(conversation.getSalesAgentId(), "Customer replied",
                    conversation.getCustomerName() + ": " + message.trim(), "info");
        } else if (conversation.getCustomerId() != null && !user.getId().equals(conversation.getCustomerId())) {
            notificationService.create(conversation.getCustomerId(), "Sales agent replied",
                    user.getFullName() + " responded to your inquiry", "info");
        }

        return saved;
    }

    public Map<String, Object> conversationDetail(String userId, String conversationId) {
        Conversation conversation = requireAccess(userId, conversationId);
        List<ConversationMessage> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        return Map.of("conversation", conversation, "messages", messages);
    }

    private Conversation requireAccess(String userId, String conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("Conversation not found"));
        User user = requestUserService.requireUser(userId);
        String role = user.getRole() == null ? "" : user.getRole().toUpperCase();

        if (user.getId().equals(conversation.getCustomerId())) {
            return conversation;
        }
        if (user.getId().equals(conversation.getSalesAgentId())) {
            return conversation;
        }
        if (role.equals("ADMIN") || role.equals("SUPERVISOR")) {
            return conversation;
        }
        throw new RuntimeException("Conversation not found");
    }
}
