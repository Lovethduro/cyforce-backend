package com.cyforce.service;

import com.cyforce.model.Conversation;
import com.cyforce.model.ConversationMessage;
import com.cyforce.model.Invoice;
import com.cyforce.model.Lead;
import com.cyforce.model.User;
import com.cyforce.repository.ConversationMessageRepository;
import com.cyforce.repository.ConversationRepository;
import com.cyforce.repository.InvoiceRepository;
import com.cyforce.repository.UserRepository;
import com.cyforce.util.SensitiveDataMasker;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MessagingService {

    private static final String FRONTEND_URL = "http://localhost:3000";
    private static final int GUEST_TOKEN_DAYS = 90;
    private static final int CUSTOMER_CHAT_EXPIRY_DAYS = 30;

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final InvoiceRepository invoiceRepository;
    private final UserRepository userRepository;
    private final RequestUserService requestUserService;
    private final NotificationService notificationService;
    private final EmailService emailService;

    public MessagingService(ConversationRepository conversationRepository,
                            ConversationMessageRepository messageRepository,
                            InvoiceRepository invoiceRepository,
                            UserRepository userRepository,
                            RequestUserService requestUserService,
                            NotificationService notificationService,
                            EmailService emailService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.invoiceRepository = invoiceRepository;
        this.userRepository = userRepository;
        this.requestUserService = requestUserService;
        this.notificationService = notificationService;
        this.emailService = emailService;
    }

    public List<Map<String, Object>> customerConversations(String userId) {
        User customer = requestUserService.requireUser(userId);
        requestUserService.requireRole(customer, "CUSTOMER");
        boolean mask = SensitiveDataMasker.shouldMaskForRole(customer.getRole());
        return conversationRepository.findByCustomerIdOrderByUpdatedAtDesc(customer.getId()).stream()
                .map(c -> toConversationView(c, mask))
                .toList();
    }

    public List<Map<String, Object>> salesConversations(String userId) {
        User agent = requestUserService.requireUser(userId);
        String role = agent.getRole() == null ? "" : agent.getRole().toUpperCase();
        List<Conversation> conversations;
        if ("SUPERVISOR".equals(role)) {
            conversations = conversationRepository.findBySupervisorIdOrderByUpdatedAtDesc(agent.getId());
        } else if ("ADMIN".equals(role)) {
            conversations = conversationRepository.findAll().stream()
                    .sorted(Comparator.comparing(Conversation::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        } else {
            requestUserService.requireRole(agent, "SALES_AGENT");
            conversations = conversationRepository.findBySalesAgentIdOrderByUpdatedAtDesc(agent.getId());
        }
        boolean mask = SensitiveDataMasker.shouldMaskForRole(role);
        return conversations.stream().map(c -> toConversationView(c, mask)).toList();
    }

    public List<Map<String, Object>> conversationQueue(String userId) {
        User agent = requestUserService.requireUser(userId);
        requestUserService.requireRole(agent, "SALES_AGENT", "ADMIN", "SUPERVISOR");
        boolean mask = SensitiveDataMasker.shouldMaskForRole(agent.getRole());
        return conversationRepository.findByStatusOrderByCreatedAtDesc("unassigned").stream()
                .map(c -> toConversationView(c, mask))
                .toList();
    }

    public Conversation acceptConversation(String userId, String conversationId) {
        User agent = requestUserService.requireUser(userId);
        requestUserService.requireRole(agent, "SALES_AGENT");

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("Conversation not found"));

        if (!"unassigned".equalsIgnoreCase(conversation.getStatus())) {
            throw new RuntimeException("This conversation has already been accepted by another agent");
        }

        conversation.setSalesAgentId(agent.getId());
        conversation.setSalesAgentName(agent.getFullName());
        conversation.setSalesAgentAvatarUrl(resolveAvatar(agent));
        conversation.setStatus("open");
        conversation.setUpdatedAt(LocalDateTime.now());
        touchConversationExpiry(conversation);
        Conversation saved = conversationRepository.save(conversation);

        if (conversation.getCustomerId() != null) {
            notificationService.create(conversation.getCustomerId(), "Sales agent assigned",
                    agent.getFullName() + " is now handling your inquiry.", "info");
        }

        ConversationMessage entry = new ConversationMessage();
        entry.setConversationId(conversation.getId());
        entry.setAuthorId(agent.getId());
        entry.setAuthorName(agent.getFullName());
        entry.setAuthorRole(agent.getRole());
        entry.setAuthorAvatarUrl(resolveAvatar(agent));
        entry.setMessageType("system");
        entry.setMessage(agent.getFullName() + " accepted this conversation.");
        entry.setCreatedAt(LocalDateTime.now());
        messageRepository.save(entry);

        return saved;
    }

    public Conversation createQuoteConversation(Lead lead, User agent) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String subject = "Quote: " + formatQuoteSubject(lead.getQuoteType());

        Conversation conversation = new Conversation();
        conversation.setCustomerName(lead.getName());
        conversation.setCustomerEmail(lead.getEmail());
        conversation.setLeadId(lead.getId());
        conversation.setGuestAccessToken(token);
        LocalDateTime guestExpiry = LocalDateTime.now().plusDays(GUEST_TOKEN_DAYS);
        conversation.setGuestTokenExpiresAt(guestExpiry);
        conversation.setExpiresAt(guestExpiry);
        conversation.setSubject(subject);
        conversation.setSalesAgentId(agent.getId());
        conversation.setSalesAgentName(agent.getFullName());
        conversation.setSalesAgentAvatarUrl(resolveAvatar(agent));
        conversation.setStatus("open");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        Conversation saved = conversationRepository.save(conversation);

        ConversationMessage entry = new ConversationMessage();
        entry.setConversationId(saved.getId());
        entry.setAuthorId("system");
        entry.setAuthorName("CyForce");
        entry.setAuthorRole("SYSTEM");
        entry.setMessageType("system");
        entry.setMessage("Quote request received. " + (lead.getDetails() != null ? lead.getDetails() : subject));
        entry.setCreatedAt(LocalDateTime.now());
        messageRepository.save(entry);

        return saved;
    }

    public Map<String, Object> guestConversationDetail(String token) {
        Conversation conversation = requireValidGuestToken(token);
        List<ConversationMessage> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversation", toGuestConversationView(conversation));
        result.put("messages", messages.stream().map(m -> toMessageView(m, false)).toList());
        return result;
    }

    public ConversationMessage guestSendMessage(String token, String message) {
        if (message == null || message.isBlank()) {
            throw new RuntimeException("Message is required");
        }
        Conversation conversation = requireValidGuestToken(token);
        ensureConversationActive(conversation);
        if ("closed".equalsIgnoreCase(conversation.getStatus())) {
            throw new RuntimeException("This conversation is closed");
        }

        ConversationMessage entry = new ConversationMessage();
        entry.setConversationId(conversation.getId());
        entry.setAuthorId("guest");
        entry.setAuthorName(conversation.getCustomerName());
        entry.setAuthorRole("GUEST");
        entry.setMessage(message.trim());
        entry.setMessageType("text");
        entry.setCreatedAt(LocalDateTime.now());
        ConversationMessage saved = messageRepository.save(entry);

        conversation.setUpdatedAt(LocalDateTime.now());
        touchConversationExpiry(conversation);
        conversationRepository.save(conversation);

        if (conversation.getSalesAgentId() != null) {
            notificationService.create(conversation.getSalesAgentId(), "Quote prospect replied",
                    conversation.getCustomerName() + ": " + message.trim(), "info");
        }

        return saved;
    }

    public String guestPortalUrl(Conversation conversation) {
        if (conversation.getGuestAccessToken() == null) {
            return null;
        }
        return FRONTEND_URL + "/quote/portal/" + conversation.getGuestAccessToken();
    }

    public Conversation findConversationForLead(String leadId) {
        return conversationRepository.findByLeadId(leadId).orElse(null);
    }

    public ConversationMessage appendAgentEmailToConversation(User agent, Conversation conversation,
                                                              String subject, String body) {
        String note = "Email sent: " + (subject == null || subject.isBlank() ? "Follow-up" : subject.trim());
        if (body != null && !body.isBlank()) {
            note = note + "\n\n" + body.trim();
        }

        ConversationMessage entry = new ConversationMessage();
        entry.setConversationId(conversation.getId());
        entry.setAuthorId(agent.getId());
        entry.setAuthorName(agent.getFullName());
        entry.setAuthorRole(agent.getRole());
        entry.setAuthorAvatarUrl(resolveAvatar(agent));
        entry.setMessageType("email");
        entry.setMessage(note);
        entry.setCreatedAt(LocalDateTime.now());
        ConversationMessage saved = messageRepository.save(entry);

        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);
        return saved;
    }

    public Conversation startConversation(String userId, String subject, String message) {
        return startConversation(userId, subject, message, null);
    }

    public Conversation startConversation(String userId, String subject, String message, String ticketId) {
        User customer = requestUserService.requireUser(userId);
        requestUserService.requireRole(customer, "CUSTOMER");

        Conversation conversation = new Conversation();
        conversation.setCustomerId(customer.getId());
        conversation.setCustomerName(customer.getFullName());
        conversation.setCustomerEmail(customer.getEmail());
        conversation.setSubject(subject != null && !subject.isBlank() ? subject.trim() : "Sales inquiry");
        conversation.setStatus("unassigned");
        conversation.setTicketId(ticketId);
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        touchConversationExpiry(conversation);
        Conversation saved = conversationRepository.save(conversation);

        if (message != null && !message.isBlank()) {
            sendMessage(userId, saved.getId(), message, null);
        }

        userRepository.findAll().stream()
                .filter(u -> "SALES_AGENT".equalsIgnoreCase(u.getRole()) && u.isActive())
                .forEach(agent -> notificationService.create(agent.getId(), "New sales inquiry",
                        customer.getFullName() + ": " + conversation.getSubject(), "info"));

        return conversationRepository.findById(saved.getId()).orElse(saved);
    }

    public Conversation startConversationForCustomer(User customer, String subject, String message, String ticketId) {
        Conversation conversation = new Conversation();
        conversation.setCustomerId(customer.getId());
        conversation.setCustomerName(customer.getFullName());
        conversation.setCustomerEmail(customer.getEmail());
        conversation.setSubject(subject != null && !subject.isBlank() ? subject.trim() : "Sales inquiry");
        conversation.setStatus("unassigned");
        conversation.setTicketId(ticketId);
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        touchConversationExpiry(conversation);
        Conversation saved = conversationRepository.save(conversation);

        if (message != null && !message.isBlank()) {
            ConversationMessage entry = new ConversationMessage();
            entry.setConversationId(saved.getId());
            entry.setAuthorId(customer.getId());
            entry.setAuthorName(customer.getFullName());
            entry.setAuthorRole(customer.getRole());
            entry.setAuthorAvatarUrl(resolveAvatar(customer));
            entry.setMessage(message.trim());
            entry.setMessageType("text");
            entry.setCreatedAt(LocalDateTime.now());
            messageRepository.save(entry);
        }

        userRepository.findAll().stream()
                .filter(u -> "SALES_AGENT".equalsIgnoreCase(u.getRole()) && u.isActive())
                .forEach(agent -> notificationService.create(agent.getId(), "New sales inquiry",
                        customer.getFullName() + ": " + conversation.getSubject(), "info"));

        return saved;
    }

    public List<Map<String, Object>> getMessages(String userId, String conversationId) {
        Conversation conversation = requireAccess(userId, conversationId);
        User viewer = requestUserService.requireUser(userId);
        boolean mask = SensitiveDataMasker.shouldMaskForRole(viewer.getRole());
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId()).stream()
                .map(m -> toMessageView(m, mask))
                .toList();
    }

    public ConversationMessage sendMessage(String userId, String conversationId, String message, String attachmentUrl) {
        if (message == null || message.isBlank()) {
            throw new RuntimeException("Message is required");
        }
        User user = requestUserService.requireUser(userId);
        if ("ADMIN".equalsIgnoreCase(user.getRole())) {
            throw new RuntimeException("Administrators have read-only access to sales conversations");
        }
        Conversation conversation = requireAccess(userId, conversationId);
        ensureConversationActive(conversation);

        ConversationMessage entry = new ConversationMessage();
        entry.setConversationId(conversation.getId());
        entry.setAuthorId(user.getId());
        entry.setAuthorName(user.getFullName());
        entry.setAuthorRole(user.getRole());
        entry.setAuthorAvatarUrl(resolveAvatar(user));
        entry.setMessage(message.trim());
        entry.setAttachmentUrl(attachmentUrl);
        entry.setCreatedAt(LocalDateTime.now());
        ConversationMessage saved = messageRepository.save(entry);

        conversation.setUpdatedAt(LocalDateTime.now());
        touchConversationExpiry(conversation);
        conversationRepository.save(conversation);

        if (user.getId().equals(conversation.getCustomerId())) {
            if (conversation.getSalesAgentId() != null) {
                notificationService.create(conversation.getSalesAgentId(), "Customer replied",
                        conversation.getCustomerName() + ": " + message.trim(), "info");
            } else if ("unassigned".equalsIgnoreCase(conversation.getStatus())) {
                userRepository.findAll().stream()
                        .filter(u -> "SALES_AGENT".equalsIgnoreCase(u.getRole()) && u.isActive())
                        .forEach(agent -> notificationService.create(agent.getId(), "Customer replied",
                                conversation.getCustomerName() + ": " + message.trim(), "info"));
            }
        } else if (conversation.getCustomerId() != null && !user.getId().equals(conversation.getCustomerId())) {
            notificationService.create(conversation.getCustomerId(), "Sales agent replied",
                    user.getFullName() + " responded to your inquiry", "info");
        } else if (isGuestConversation(conversation)) {
            notifyGuestByEmail(conversation, user.getFullName(), message.trim());
        }

        return saved;
    }

    public ConversationMessage sendInvoice(String userId, String conversationId, long amountNaira, String description) {
        User agent = requestUserService.requireUser(userId);
        Conversation conversation = requireAccess(userId, conversationId);
        if (!userId.equals(conversation.getSalesAgentId())) {
            throw new RuntimeException("Only the assigned sales agent can send an invoice");
        }
        if (amountNaira < 1) {
            throw new RuntimeException("Invoice amount must be at least ₦1");
        }

        long amountKobo = amountNaira * 100L;
        String details = description != null && !description.isBlank()
                ? description.trim()
                : conversation.getSubject();

        Invoice invoice = new Invoice();
        invoice.setCustomerId(conversation.getCustomerId());
        invoice.setCustomerName(conversation.getCustomerName());
        invoice.setSalesAgentId(conversation.getSalesAgentId());
        invoice.setSalesAgentName(conversation.getSalesAgentName());
        invoice.setConversationId(conversation.getId());
        invoice.setAmount(amountKobo);
        invoice.setCurrency("NGN");
        invoice.setStatus("pending");
        invoice.setDescription(details);
        invoice.setDueDate(LocalDateTime.now().plusDays(7));
        invoice.setCreatedAt(LocalDateTime.now());
        Invoice savedInvoice = invoiceRepository.save(invoice);

        ConversationMessage entry = new ConversationMessage();
        entry.setConversationId(conversation.getId());
        entry.setAuthorId(agent.getId());
        entry.setAuthorName(agent.getFullName());
        entry.setAuthorRole(agent.getRole());
        entry.setAuthorAvatarUrl(resolveAvatar(agent));
        entry.setMessageType("invoice");
        entry.setInvoiceId(savedInvoice.getId());
        entry.setMessage("Agreed price: ₦" + String.format("%,d", amountNaira) + " — " + details);
        entry.setCreatedAt(LocalDateTime.now());
        ConversationMessage saved = messageRepository.save(entry);

        conversation.setLinkedInvoiceId(savedInvoice.getId());
        conversation.setAgreedAmountKobo(amountKobo);
        conversation.setStatus("invoice_sent");
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);

        if (conversation.getCustomerId() != null) {
            notificationService.create(conversation.getCustomerId(), "Invoice from sales",
                    agent.getFullName() + " sent you an invoice for ₦" + String.format("%,d", amountNaira), "info");
        } else if (isGuestConversation(conversation)) {
            notifyGuestByEmail(conversation, agent.getFullName(),
                    "Invoice for ₦" + String.format("%,d", amountNaira) + " — " + details);
        }

        return saved;
    }

    public Conversation forwardToSupervisor(String userId, String conversationId, String reason) {
        User actor = requestUserService.requireUser(userId);
        Conversation conversation = requireAccess(userId, conversationId);
        if (!userId.equals(conversation.getSalesAgentId())) {
            throw new RuntimeException("Only the assigned sales agent can forward this conversation");
        }

        User supervisor = userRepository.findAll().stream()
                .filter(u -> "SUPERVISOR".equalsIgnoreCase(u.getRole()) && u.isActive())
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No supervisor is available right now"));

        conversation.setSupervisorId(supervisor.getId());
        conversation.setSupervisorName(supervisor.getFullName());
        conversation.setForwardedAt(LocalDateTime.now());
        conversation.setStatus("forwarded");
        conversation.setUpdatedAt(LocalDateTime.now());
        Conversation saved = conversationRepository.save(conversation);

        String note = reason != null && !reason.isBlank() ? reason.trim() : "Needs supervisor review";
        ConversationMessage entry = new ConversationMessage();
        entry.setConversationId(conversation.getId());
        entry.setAuthorId(actor.getId());
        entry.setAuthorName(actor.getFullName());
        entry.setAuthorRole(actor.getRole());
        entry.setAuthorAvatarUrl(resolveAvatar(actor));
        entry.setMessageType("system");
        entry.setMessage(actor.getFullName() + " forwarded this conversation to " + supervisor.getFullName() + ". Reason: " + note);
        entry.setCreatedAt(LocalDateTime.now());
        messageRepository.save(entry);

        notificationService.create(supervisor.getId(), "Sales escalation",
                actor.getFullName() + " forwarded a customer conversation: " + conversation.getSubject(), "warning");
        if (conversation.getCustomerId() != null) {
            notificationService.create(conversation.getCustomerId(), "Supervisor joined",
                    supervisor.getFullName() + " has been added to assist with your request.", "info");
        }

        return saved;
    }

    public Map<String, Object> conversationDetail(String userId, String conversationId) {
        Conversation conversation = requireAccess(userId, conversationId);
        User viewer = requestUserService.requireUser(userId);
        boolean mask = SensitiveDataMasker.shouldMaskForRole(viewer.getRole());
        List<ConversationMessage> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        List<Map<String, Object>> enriched = messages.stream().map(m -> toMessageView(m, mask)).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversation", toConversationView(conversation, mask));
        result.put("messages", enriched);
        result.put("sensitiveDataMasked", mask);
        return result;
    }

    private Map<String, Object> toConversationView(Conversation conversation, boolean maskSensitive) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", conversation.getId());
        view.put("customerId", conversation.getCustomerId());
        view.put("customerName", conversation.getCustomerName());
        view.put("customerEmail", maskSensitive
                ? SensitiveDataMasker.maskEmail(conversation.getCustomerEmail())
                : conversation.getCustomerEmail());
        view.put("leadId", conversation.getLeadId());
        view.put("isGuest", isGuestConversation(conversation));
        view.put("salesAgentId", conversation.getSalesAgentId());
        view.put("salesAgentName", conversation.getSalesAgentName());
        view.put("salesAgentAvatarUrl", conversation.getSalesAgentAvatarUrl());
        view.put("subject", conversation.getSubject());
        view.put("status", conversation.getStatus());
        view.put("ticketId", conversation.getTicketId());
        view.put("supervisorId", conversation.getSupervisorId());
        view.put("supervisorName", conversation.getSupervisorName());
        view.put("linkedInvoiceId", conversation.getLinkedInvoiceId());
        view.put("agreedAmountKobo", conversation.getAgreedAmountKobo());
        view.put("forwardedAt", conversation.getForwardedAt());
        view.put("createdAt", conversation.getCreatedAt());
        view.put("updatedAt", conversation.getUpdatedAt());
        view.put("expiresAt", effectiveExpiry(conversation));
        view.put("guestTokenExpiresAt", conversation.getGuestTokenExpiresAt());
        view.put("closedAt", conversation.getClosedAt());
        view.put("closeReason", conversation.getCloseReason());
        view.put("customerRating", conversation.getCustomerRating());
        view.put("ratingComment", maskSensitive
                ? SensitiveDataMasker.redactText(conversation.getRatingComment())
                : conversation.getRatingComment());
        view.put("ratedAt", conversation.getRatedAt());
        if (conversation.getSalesAgentId() != null) {
            userRepository.findById(conversation.getSalesAgentId()).ifPresent(agent -> {
                view.put("salesAgentAverageRating", agent.getAverageRating());
                view.put("salesAgentRatingCount", agent.getRatingCount());
            });
        }
        return view;
    }

    private Map<String, Object> toMessageView(ConversationMessage message, boolean maskSensitive) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", message.getId());
        item.put("conversationId", message.getConversationId());
        item.put("authorId", message.getAuthorId());
        item.put("authorName", message.getAuthorName());
        item.put("authorRole", message.getAuthorRole());
        item.put("authorAvatarUrl", message.getAuthorAvatarUrl());
        item.put("message", maskSensitive
                ? SensitiveDataMasker.redactText(message.getMessage())
                : message.getMessage());
        item.put("messageType", message.getMessageType() != null ? message.getMessageType() : "text");
        item.put("invoiceId", message.getInvoiceId());
        item.put("attachmentUrl", message.getAttachmentUrl());
        item.put("createdAt", message.getCreatedAt());

        if ("invoice".equals(message.getMessageType()) && message.getInvoiceId() != null) {
            invoiceRepository.findById(message.getInvoiceId()).ifPresent(inv -> {
                String description = inv.getDescription() != null ? inv.getDescription() : "";
                item.put("invoice", Map.of(
                        "id", inv.getId(),
                        "amount", inv.getAmount(),
                        "status", inv.getStatus(),
                        "description", maskSensitive ? SensitiveDataMasker.redactText(description) : description
                ));
            });
        }
        return item;
    }

    private Conversation requireAccess(String userId, String conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("Conversation not found"));
        User user = requestUserService.requireUser(userId);
        String role = user.getRole() == null ? "" : user.getRole().toUpperCase();

        if (user.getId().equals(conversation.getCustomerId())) {
            ensureConversationActive(conversation);
            return conversation;
        }
        if (user.getId().equals(conversation.getSalesAgentId())) {
            return conversation;
        }
        if (conversation.getSupervisorId() != null && user.getId().equals(conversation.getSupervisorId())) {
            return conversation;
        }
        if (role.equals("ADMIN") || role.equals("SUPERVISOR")) {
            return conversation;
        }
        throw new RuntimeException("Conversation not found");
    }

    private String resolveAvatar(User user) {
        if (user.getAvatarUrl() != null && !user.getAvatarUrl().isBlank()) {
            return user.getAvatarUrl();
        }
        if (user.getLogoUrl() != null && !user.getLogoUrl().isBlank()) {
            return user.getLogoUrl();
        }
        return null;
    }

    private Conversation requireValidGuestToken(String token) {
        if (token == null || token.isBlank()) {
            throw new RuntimeException("Invalid quote link");
        }
        Conversation conversation = conversationRepository.findByGuestAccessToken(token.trim())
                .orElseThrow(() -> new RuntimeException("Quote link is invalid or expired"));
        LocalDateTime expiry = effectiveExpiry(conversation);
        if (expiry != null && expiry.isBefore(LocalDateTime.now())) {
            closeExpiredConversation(conversation);
            throw new RuntimeException("Quote link has expired. Please contact sales@cyforcetech.com for help.");
        }
        return conversation;
    }

    private Map<String, Object> toGuestConversationView(Conversation conversation) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("subject", conversation.getSubject());
        view.put("customerName", conversation.getCustomerName());
        view.put("salesAgentName", conversation.getSalesAgentName());
        view.put("status", conversation.getStatus());
        view.put("createdAt", conversation.getCreatedAt());
        view.put("updatedAt", conversation.getUpdatedAt());
        view.put("expiresAt", effectiveExpiry(conversation));
        view.put("guestTokenExpiresAt", conversation.getGuestTokenExpiresAt());
        return view;
    }

    private void touchConversationExpiry(Conversation conversation) {
        LocalDateTime now = LocalDateTime.now();
        if (isGuestConversation(conversation)) {
            LocalDateTime guestExpiry = now.plusDays(GUEST_TOKEN_DAYS);
            conversation.setGuestTokenExpiresAt(guestExpiry);
            conversation.setExpiresAt(guestExpiry);
            return;
        }
        conversation.setExpiresAt(now.plusDays(CUSTOMER_CHAT_EXPIRY_DAYS));
    }

    private LocalDateTime effectiveExpiry(Conversation conversation) {
        if (conversation.getGuestTokenExpiresAt() != null) {
            return conversation.getGuestTokenExpiresAt();
        }
        if (conversation.getExpiresAt() != null) {
            return conversation.getExpiresAt();
        }
        if (conversation.getUpdatedAt() != null) {
            return conversation.getUpdatedAt().plusDays(CUSTOMER_CHAT_EXPIRY_DAYS);
        }
        if (conversation.getCreatedAt() != null) {
            return conversation.getCreatedAt().plusDays(CUSTOMER_CHAT_EXPIRY_DAYS);
        }
        return null;
    }

    private void ensureConversationActive(Conversation conversation) {
        if ("closed".equalsIgnoreCase(conversation.getStatus())) {
            return;
        }
        LocalDateTime expiry = effectiveExpiry(conversation);
        if (expiry != null && expiry.isBefore(LocalDateTime.now())) {
            closeExpiredConversation(conversation);
            throw new RuntimeException("This conversation has expired. Please start a new chat if you still need help.");
        }
    }

    private void closeExpiredConversation(Conversation conversation) {
        if ("closed".equalsIgnoreCase(conversation.getStatus())) {
            return;
        }
        conversation.setStatus("closed");
        conversation.setCloseReason("expired");
        conversation.setClosedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);
    }

    private boolean isGuestConversation(Conversation conversation) {
        return conversation.getGuestAccessToken() != null && !conversation.getGuestAccessToken().isBlank();
    }

    private void notifyGuestByEmail(Conversation conversation, String agentName, String messagePreview) {
        if (conversation.getCustomerEmail() == null || conversation.getCustomerEmail().isBlank()) {
            return;
        }
        try {
            emailService.sendQuoteAgentReplyEmail(
                    conversation.getCustomerEmail(),
                    conversation.getCustomerName(),
                    agentName,
                    messagePreview,
                    guestPortalUrl(conversation)
            );
        } catch (RuntimeException e) {
            System.err.println("Failed to email quote prospect: " + e.getMessage());
        }
    }

    private String formatQuoteSubject(String quoteType) {
        if (quoteType == null) {
            return "Request";
        }
        return switch (quoteType) {
            case "products_only" -> "Products Only";
            case "products_installation" -> "Products + Installation";
            case "installation_only" -> "Installation Only";
            default -> quoteType.replace('_', ' ');
        };
    }
}
