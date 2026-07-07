package com.cyforce.service;

import com.cyforce.model.Conversation;
import com.cyforce.model.AgentPresence;
import com.cyforce.model.Ticket;
import com.cyforce.model.TicketMessage;
import com.cyforce.model.User;
import com.cyforce.repository.AgentPresenceRepository;
import com.cyforce.repository.TicketMessageRepository;
import com.cyforce.repository.TicketRepository;
import com.cyforce.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final TicketMessageRepository messageRepository;
    private final AgentPresenceRepository presenceRepository;
    private final UserRepository userRepository;
    private final RequestUserService requestUserService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final FileStorageService fileStorageService;
    private final MessagingService messagingService;
    private final EmailService emailService;
    private final TicketMetricsService metricsService;

    private static final String FRONTEND_URL = "http://localhost:3000";
    private static final int GUEST_TOKEN_DAYS = 90;

    public TicketService(TicketRepository ticketRepository,
                         TicketMessageRepository messageRepository,
                         AgentPresenceRepository presenceRepository,
                         UserRepository userRepository,
                         RequestUserService requestUserService,
                         NotificationService notificationService,
                         AuditLogService auditLogService,
                         FileStorageService fileStorageService,
                         MessagingService messagingService,
                         EmailService emailService,
                         TicketMetricsService metricsService) {
        this.ticketRepository = ticketRepository;
        this.messageRepository = messageRepository;
        this.presenceRepository = presenceRepository;
        this.userRepository = userRepository;
        this.requestUserService = requestUserService;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.fileStorageService = fileStorageService;
        this.messagingService = messagingService;
        this.emailService = emailService;
        this.metricsService = metricsService;
    }

    public int processSlaEscalations() {
        List<Ticket> openTickets = ticketRepository.findByStatusInOrderByCreatedAtDesc(List.of("open", "in_progress"));
        LocalDateTime now = LocalDateTime.now();
        int processed = 0;
        for (Ticket ticket : openTickets) {
            if (!metricsService.isSlaBreached(ticket) || ticket.isSlaEscalated()) {
                continue;
            }
            escalateBreachedTicket(ticket, now);
            processed++;
        }
        return processed;
    }

    public List<Ticket> customerTickets(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "CUSTOMER");
        return ticketRepository.findByCustomerIdOrderByCreatedAtDesc(user.getId());
    }

    public List<Ticket> supportTickets(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SUPPORT_AGENT", "ADMIN", "SUPERVISOR");
        return ticketRepository.findTop200ByAssigneeIdOrderByCreatedAtDesc(user.getId());
    }

    public List<Ticket> allOpenTickets(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SUPPORT_AGENT", "ADMIN", "SUPERVISOR");
        return ticketRepository.findTop200ByStatusInOrderByCreatedAtDesc(List.of("open", "in_progress"));
    }

    public List<Ticket> allTickets(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "ADMIN", "SUPERVISOR");
        return ticketRepository.findTop200ByOrderByCreatedAtDesc();
    }

    public Ticket createTicket(String userId, Map<String, String> body) {
        return createTicket(userId, body, null);
    }

    public Ticket createTicket(String userId, Map<String, String> body, MultipartFile attachment) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "CUSTOMER");

        Ticket ticket = new Ticket();
        ticket.setCustomerId(user.getId());
        ticket.setCustomerName(user.getFullName());
        ticket.setCustomerEmail(user.getEmail());
        ticket.setSubject(body.get("subject"));
        ticket.setDescription(body.get("description"));
        ticket.setCategory(body.getOrDefault("category", "General"));
        ticket.setPriority(body.getOrDefault("priority", "medium"));
        AgentPresence autoAssignee = pickAvailableSupportAgent();
        if (autoAssignee != null) {
            User agentUser = userRepository.findById(autoAssignee.getUserId()).orElse(null);
            ticket.setAssigneeId(autoAssignee.getUserId());
            ticket.setAssigneeName(autoAssignee.getFullName());
            ticket.setAssigneeAvatarUrl(agentUser != null ? agentUser.getAvatarUrl() : null);
            ticket.setStatus("in_progress");
        } else {
            ticket.setStatus("open");
        }
        if (attachment != null && !attachment.isEmpty()) {
            ticket.setAttachmentUrl(fileStorageService.storeTicketAttachment(attachment));
        }
        ticket.setCreatedAt(LocalDateTime.now());
        ticket.setUpdatedAt(LocalDateTime.now());
        Ticket saved = ticketRepository.save(ticket);
        auditLogService.log(user, "TICKET_CREATE", "Ticketing", saved.getSubject());

        notificationService.createOnce(user.getId(), saved.getId() + ":created", "Ticket created",
                "Your support ticket \"" + saved.getSubject() + "\" has been submitted.", "info");

        if (autoAssignee != null && autoAssignee.getUserId() != null) {
            notificationService.create(
                    autoAssignee.getUserId(),
                    "New support ticket assigned",
                    saved.getCustomerName() + ": " + saved.getSubject(),
                    "info"
            );
        } else {
            notifySupportAgentsOfNewTicket(saved);
        }

        return saved;
    }

    public Map<String, Object> createGuestTicket(Map<String, String> body, MultipartFile attachment) {
        String name = stringVal(body.get("name"));
        String email = stringVal(body.get("email")).toLowerCase();
        String subject = stringVal(body.get("subject"));
        String description = stringVal(body.get("description"));
        String category = stringVal(body.get("category"));
        String priority = stringVal(body.get("priority"));

        if (name.isBlank()) throw new RuntimeException("Name is required");
        if (email.isBlank() || !email.contains("@")) throw new RuntimeException("A valid email is required");
        if (subject.isBlank()) throw new RuntimeException("Subject is required");
        if (description.isBlank()) throw new RuntimeException("Description is required");
        if (category.isBlank()) throw new RuntimeException("Category is required");

        String token = UUID.randomUUID().toString().replace("-", "");
        Ticket ticket = new Ticket();
        ticket.setCustomerId(null);
        ticket.setCustomerName(name.trim());
        ticket.setCustomerEmail(email.trim());
        ticket.setSubject(subject.trim());
        ticket.setDescription(description.trim());
        ticket.setCategory(category);
        ticket.setPriority(priority.isBlank() ? "medium" : priority.toLowerCase());
        AgentPresence autoAssignee = pickAvailableSupportAgent();
        if (autoAssignee != null) {
            User agentUser = userRepository.findById(autoAssignee.getUserId()).orElse(null);
            ticket.setAssigneeId(autoAssignee.getUserId());
            ticket.setAssigneeName(autoAssignee.getFullName());
            ticket.setAssigneeAvatarUrl(agentUser != null ? agentUser.getAvatarUrl() : null);
            ticket.setStatus("in_progress");
        } else {
            ticket.setStatus("open");
        }
        ticket.setGuestAccessToken(token);
        ticket.setGuestTokenExpiresAt(LocalDateTime.now().plusDays(GUEST_TOKEN_DAYS));
        if (attachment != null && !attachment.isEmpty()) {
            ticket.setAttachmentUrl(fileStorageService.storeTicketAttachment(attachment));
        }
        ticket.setCreatedAt(LocalDateTime.now());
        ticket.setUpdatedAt(LocalDateTime.now());
        Ticket saved = ticketRepository.save(ticket);

        if (autoAssignee != null && autoAssignee.getUserId() != null) {
            notificationService.create(
                    autoAssignee.getUserId(),
                    "New guest support ticket assigned",
                    saved.getCustomerName() + ": " + saved.getSubject(),
                    "info"
            );
        } else {
            notifySupportAgentsOfNewTicket(saved);
        }
        String portalUrl = guestTicketPortalUrl(saved);
        try {
            emailService.sendGuestTicketConfirmationEmail(
                    saved.getCustomerEmail(),
                    saved.getCustomerName(),
                    saved.getSubject(),
                    portalUrl
            );
        } catch (RuntimeException e) {
            System.err.println("Failed to send guest ticket confirmation email: " + e.getMessage());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ticketId", saved.getId());
        response.put("portalUrl", portalUrl);
        response.put("message", "Thank you! Check your email for a link to track your ticket and reply to our team.");
        return response;
    }

    public Map<String, Object> guestTicketDetail(String token) {
        Ticket ticket = requireValidGuestTicket(token);
        List<TicketMessage> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId()).stream()
                .filter(m -> !m.isInternalNote())
                .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ticket", toGuestTicketView(ticket));
        response.put("messages", messages);
        return response;
    }

    public TicketMessage guestTicketReply(String token, String message) {
        if (message == null || message.isBlank()) {
            throw new RuntimeException("Message is required");
        }
        Ticket ticket = requireValidGuestTicket(token);
        if ("closed".equalsIgnoreCase(ticket.getStatus()) || "resolved".equalsIgnoreCase(ticket.getStatus())) {
            throw new RuntimeException("This ticket is closed");
        }

        TicketMessage entry = new TicketMessage();
        entry.setTicketId(ticket.getId());
        entry.setAuthorId(null);
        entry.setAuthorName(ticket.getCustomerName());
        entry.setMessage(message.trim());
        entry.setInternalNote(false);
        entry.setCreatedAt(LocalDateTime.now());
        ticket.setUpdatedAt(LocalDateTime.now());
        ticketRepository.save(ticket);

        if (ticket.getAssigneeId() != null) {
            notificationService.create(ticket.getAssigneeId(), "Guest replied on ticket",
                    ticket.getCustomerName() + " replied on \"" + ticket.getSubject() + "\"", "info");
        } else {
            notifySupportAgentsOfNewTicket(ticket);
        }

        return messageRepository.save(entry);
    }

    public Ticket getTicket(String userId, String ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));
        User user = requestUserService.requireUser(userId);
        if (!canAccess(user, ticket)) {
            throw new RuntimeException("Ticket not found");
        }
        return ticket;
    }

    public List<TicketMessage> getMessages(String userId, String ticketId) {
        getTicket(userId, ticketId);
        List<TicketMessage> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
        User user = requestUserService.requireUser(userId);
        if (isCustomer(user)) {
            return messages.stream().filter(m -> !m.isInternalNote()).toList();
        }
        return messages;
    }

    public TicketMessage customerReply(String userId, String ticketId, String message) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "CUSTOMER");
        Ticket ticket = getTicket(userId, ticketId);
        if (!user.getId().equals(ticket.getCustomerId())) {
            throw new RuntimeException("Ticket not found");
        }
        return saveMessage(user, ticket, message, false);
    }

    public Ticket assignToMe(String userId, String ticketId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SUPPORT_AGENT", "ADMIN", "SUPERVISOR");
        if (isAdmin(user)) {
            throw new RuntimeException("Use ticket takeover instead of self-assignment");
        }
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));
        ticket.setAssigneeId(user.getId());
        ticket.setAssigneeName(user.getFullName());
        ticket.setAssigneeAvatarUrl(resolveAvatar(user));
        ticket.setStatus("in_progress");
        ticket.setUpdatedAt(LocalDateTime.now());
        return ticketRepository.save(ticket);
    }

    public Ticket updateStatus(String userId, String ticketId, String status) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SUPPORT_AGENT", "ADMIN", "SUPERVISOR");
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));
        if (isAdmin(user) && !ticket.isAdminTakeover() && !ticket.isSlaEscalated()) {
            throw new RuntimeException("Take over this ticket before changing its status");
        }
        ticket.setStatus(status);
        ticket.setUpdatedAt(LocalDateTime.now());
        Ticket saved = ticketRepository.save(ticket);
        if (ticket.getCustomerId() != null) {
            notificationService.createOnce(ticket.getCustomerId(), ticket.getId() + ":status:" + status, "Ticket updated",
                    "Your ticket \"" + ticket.getSubject() + "\" is now " + status, "info");
        }
        return saved;
    }

    public TicketMessage addResponse(String userId, String ticketId, String message, boolean internalNote) {
        User user = requestUserService.requireUser(userId);
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));

        if (internalNote) {
            requestUserService.requireRole(user, "SUPPORT_AGENT", "ADMIN", "SUPERVISOR");
        } else if (isCustomer(user)) {
            if (!user.getId().equals(ticket.getCustomerId())) {
                throw new RuntimeException("Ticket not found");
            }
        } else {
            requestUserService.requireRole(user, "SUPPORT_AGENT", "ADMIN", "SUPERVISOR");
            enforceAdminReplyPolicy(user, ticket, internalNote);
        }

        return saveMessage(user, ticket, message, internalNote);
    }

    public Ticket adminTakeover(String userId, String ticketId) {
        User admin = requestUserService.requireUser(userId);
        requestUserService.requireRole(admin, "ADMIN");

        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));

        if (ticket.isTransferredToSales()) {
            throw new RuntimeException("This ticket has been transferred to sales");
        }
        if ("merged".equalsIgnoreCase(ticket.getStatus()) || "closed".equalsIgnoreCase(ticket.getStatus())) {
            throw new RuntimeException("Cannot take over a closed or merged ticket");
        }
        if (ticket.isAdminTakeover()) {
            return ticket;
        }

        ticket.setAdminTakeover(true);
        ticket.setAdminTakeoverAt(LocalDateTime.now());
        ticket.setAdminTakeoverById(admin.getId());
        ticket.setAssigneeId(admin.getId());
        ticket.setAssigneeName(admin.getFullName());
        ticket.setAssigneeAvatarUrl(resolveAvatar(admin));
        if ("open".equalsIgnoreCase(ticket.getStatus())) {
            ticket.setStatus("in_progress");
        }
        ticket.setUpdatedAt(LocalDateTime.now());
        ticketRepository.save(ticket);

        saveMessage(admin, ticket,
                admin.getFullName() + " (Administrator) has joined this conversation and will assist you.",
                false);

        if (ticket.getCustomerId() != null) {
            notificationService.create(ticket.getCustomerId(), "Administrator assigned",
                    "An administrator is now handling your support request.", "info");
        }

        auditLogService.log(admin, "TICKET_ADMIN_TAKEOVER", "Ticketing", ticket.getSubject());
        return ticket;
    }

    public Map<String, Object> transferToSales(String userId, String ticketId, String note) {
        User agent = requestUserService.requireUser(userId);
        requestUserService.requireRole(agent, "SUPPORT_AGENT", "ADMIN", "SUPERVISOR");
        if (isAdmin(agent)) {
            throw new RuntimeException("Administrators cannot transfer tickets — support agents handle handoffs");
        }

        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));

        if (ticket.isTransferredToSales() && ticket.getSalesConversationId() != null) {
            throw new RuntimeException("This ticket has already been transferred to sales");
        }

        User customer = userRepository.findById(ticket.getCustomerId())
                .orElseThrow(() -> new RuntimeException("Customer not found"));

        String subject = "Sales follow-up: " + ticket.getSubject();
        String body = (note != null && !note.isBlank() ? note.trim() + "\n\n" : "")
                + "Transferred from support ticket.\n"
                + (ticket.getDescription() != null ? ticket.getDescription() : "");

        Conversation conversation = messagingService.startConversationForCustomer(
                customer, subject, body, ticket.getId());

        ticket.setTransferredToSales(true);
        ticket.setSalesConversationId(conversation.getId());
        ticket.setTransferredAt(LocalDateTime.now());
        ticket.setStatus("transferred_to_sales");
        ticket.setUpdatedAt(LocalDateTime.now());
        ticketRepository.save(ticket);

        saveMessage(agent, ticket, "Transferred to sales team for purchase follow-up."
                + (note != null && !note.isBlank() ? " Note: " + note.trim() : ""), true);

        notificationService.create(customer.getId(), "Transferred to sales",
                "Your request was forwarded to our sales team.", "info");

        auditLogService.log(agent, "TICKET_TRANSFER_SALES", "Ticketing", ticket.getSubject());

        return Map.of(
                "ticket", ticket,
                "conversationId", conversation.getId(),
                "message", "Ticket transferred to sales queue"
        );
    }

    public Ticket transferToAgent(String userId, String ticketId, String targetAgentId, String note) {
        User agent = requestUserService.requireUser(userId);
        requestUserService.requireRole(agent, "SUPPORT_AGENT", "ADMIN", "SUPERVISOR");
        if (isAdmin(agent)) {
            throw new RuntimeException("Administrators cannot transfer tickets — support agents handle handoffs");
        }

        if (targetAgentId == null || targetAgentId.isBlank()) {
            throw new RuntimeException("Select an agent to transfer to");
        }
        if (userId.equals(targetAgentId)) {
            throw new RuntimeException("Cannot transfer a ticket to yourself");
        }

        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));

        if (ticket.isTransferredToSales()) {
            throw new RuntimeException("This ticket has already been transferred to sales");
        }
        if ("merged".equalsIgnoreCase(ticket.getStatus())) {
            throw new RuntimeException("Cannot transfer a merged ticket");
        }

        User target = userRepository.findById(targetAgentId)
                .orElseThrow(() -> new RuntimeException("Agent not found"));

        String role = target.getRole() != null ? target.getRole().toUpperCase() : "";
        if (!List.of("SUPPORT_AGENT", "SUPERVISOR", "ADMIN").contains(role)) {
            throw new RuntimeException("Tickets can only be transferred to support staff");
        }

        ticket.setAssigneeId(target.getId());
        ticket.setAssigneeName(target.getFullName());
        ticket.setAssigneeAvatarUrl(resolveAvatar(target));
        if ("open".equalsIgnoreCase(ticket.getStatus())) {
            ticket.setStatus("in_progress");
        }
        ticket.setUpdatedAt(LocalDateTime.now());
        ticketRepository.save(ticket);

        String noteText = note != null && !note.isBlank() ? note.trim() : "No handoff note provided";
        saveMessage(agent, ticket,
                "Transferred to " + target.getFullName() + ". Handoff note: " + noteText, true);

        notificationService.create(target.getId(), "Ticket transferred to you",
                "\"" + ticket.getSubject() + "\" was transferred by " + agent.getFullName(), "info");

        auditLogService.log(agent, "TICKET_TRANSFER_AGENT", "Ticketing",
                "Transferred to " + target.getFullName() + ": " + ticket.getSubject());

        return ticket;
    }

    public List<Map<String, Object>> listSupportAgents(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SUPPORT_AGENT", "ADMIN", "SUPERVISOR");
        Map<String, Long> openByAssignee = openTicketCountsByAssignee();

        return userRepository.findByRoleIn(List.of("SUPPORT_AGENT", "SUPERVISOR", "ADMIN")).stream()
                .sorted(Comparator.comparing(User::getFullName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(u -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", u.getId());
                    item.put("name", u.getFullName());
                    item.put("role", u.getRole());
                    item.put("openTickets", openByAssignee.getOrDefault(u.getId(), 0L));
                    item.put("self", u.getId().equals(userId));
                    return item;
                })
                .toList();
    }

    private Map<String, Long> openTicketCountsByAssignee() {
        return ticketRepository.findTop200ByStatusInOrderByCreatedAtDesc(List.of("open", "in_progress")).stream()
                .filter(t -> t.getAssigneeId() != null && !t.getAssigneeId().isBlank())
                .collect(Collectors.groupingBy(Ticket::getAssigneeId, Collectors.counting()));
    }

    private TicketMessage saveMessage(User user, Ticket ticket, String message, boolean internalNote) {
        TicketMessage entry = new TicketMessage();
        entry.setTicketId(ticket.getId());
        entry.setAuthorId(user.getId());
        entry.setAuthorName(user.getFullName());
        entry.setAuthorAvatarUrl(resolveAvatar(user));
        entry.setMessage(message);
        entry.setInternalNote(internalNote);
        entry.setCreatedAt(LocalDateTime.now());
        ticket.setUpdatedAt(LocalDateTime.now());
        ticketRepository.save(ticket);

        if (!internalNote && ticket.getCustomerId() != null && !user.getId().equals(ticket.getCustomerId())) {
            notificationService.create(ticket.getCustomerId(), "New ticket response",
                    "Support replied on \"" + ticket.getSubject() + "\"", "info");
        } else if (!internalNote && ticket.getCustomerId() == null
                && ticket.getGuestAccessToken() != null
                && ticket.getCustomerEmail() != null
                && !user.getId().equals(ticket.getCustomerId())) {
            try {
                emailService.sendGuestTicketAgentReplyEmail(
                        ticket.getCustomerEmail(),
                        ticket.getCustomerName(),
                        user.getFullName(),
                        message,
                        guestTicketPortalUrl(ticket)
                );
            } catch (RuntimeException e) {
                System.err.println("Failed to email guest about ticket reply: " + e.getMessage());
            }
        } else if (!internalNote && ticket.getAssigneeId() != null && user.getId().equals(ticket.getCustomerId())) {
            notificationService.create(ticket.getAssigneeId(), "Customer replied",
                    ticket.getCustomerName() + " replied on \"" + ticket.getSubject() + "\"", "info");
        }

        return messageRepository.save(entry);
    }

    public Map<String, Object> customerStats(String userId) {
        List<Ticket> tickets = customerTickets(userId);
        long open = tickets.stream().filter(t -> "open".equals(t.getStatus()) || "in_progress".equals(t.getStatus())).count();
        return Map.of("totalTickets", tickets.size(), "openTickets", open);
    }

    public Map<String, Object> supportStats(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SUPPORT_AGENT", "ADMIN", "SUPERVISOR");
        String assigneeId = user.getId();
        long assigned = ticketRepository.countByAssigneeId(assigneeId);
        long resolved = ticketRepository.countByAssigneeIdAndStatusIn(assigneeId, List.of("resolved", "closed"));
        long openQueue = ticketRepository.countByStatusIn(List.of("open", "in_progress"));
        return Map.of("assignedTickets", assigned, "resolvedTickets", resolved, "openQueue", openQueue);
    }

    public List<Map<String, String>> supportMacros(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SUPPORT_AGENT", "ADMIN", "SUPERVISOR");
        return List.of(
                macro("greeting", "Greeting", "Hi {{customerName}}, thanks for reaching out. I am reviewing this for you now."),
                macro("need-details", "Need More Details", "Could you share a screenshot, exact error text, and the steps you took right before this happened?"),
                macro("status-update", "Status Update", "Quick update: we are still investigating this and I will keep you posted as soon as we confirm the fix."),
                macro("resolved-check", "Resolution Check", "I have applied a fix on our side. Please try again and let me know if the issue is fully resolved."),
                macro("close-confirm", "Close Confirmation", "Glad this is sorted out. If everything looks good, I can close this ticket for you.")
        );
    }

    public List<Map<String, Object>> ticketTimeline(String userId, String ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));
        User user = requestUserService.requireUser(userId);
        if (!canAccess(user, ticket)) {
            throw new RuntimeException("Ticket not found");
        }

        List<Map<String, Object>> events = new java.util.ArrayList<>();

        if (ticket.getCreatedAt() != null) {
            events.add(Map.of(
                    "messageType", "system",
                    "message", "Ticket created",
                    "createdAt", ticket.getCreatedAt()
            ));
        }

        if (ticket.isSlaEscalated() && ticket.getSlaEscalatedAt() != null) {
            events.add(Map.of(
                    "messageType", "system",
                    "message", "SLA escalated",
                    "createdAt", ticket.getSlaEscalatedAt()
            ));
        }

        if (ticket.isTransferredToSales() && ticket.getTransferredAt() != null) {
            events.add(Map.of(
                    "messageType", "system",
                    "message", "Transferred to sales",
                    "createdAt", ticket.getTransferredAt()
            ));
        }

        List<TicketMessage> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
        if (isCustomer(user)) {
            messages = messages.stream().filter(m -> !m.isInternalNote()).toList();
        }

        for (TicketMessage m : messages) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", m.getId());
            event.put("messageType", "text");
            event.put("authorId", m.getAuthorId());
            event.put("authorName", m.getAuthorName());
            event.put("authorAvatarUrl", m.getAuthorAvatarUrl());
            event.put("message", m.getMessage());
            event.put("internalNote", m.isInternalNote());
            event.put("createdAt", m.getCreatedAt());
            events.add(event);
        }

        events.sort((a, b) -> {
            LocalDateTime ad = (LocalDateTime) a.get("createdAt");
            LocalDateTime bd = (LocalDateTime) b.get("createdAt");
            if (ad == null && bd == null) return 0;
            if (ad == null) return 1;
            if (bd == null) return -1;
            return ad.compareTo(bd);
        });

        return events;
    }

    public List<Map<String, Object>> findDuplicateTickets(String userId, String ticketId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SUPPORT_AGENT", "ADMIN");

        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));

        List<Ticket> candidates = findSameCustomerTickets(ticket).stream()
                .filter(t -> !ticketId.equals(t.getId()))
                .filter(t -> t.getMergedIntoTicketId() == null || t.getMergedIntoTicketId().isBlank())
                .filter(t -> !"merged".equalsIgnoreCase(t.getStatus()))
                .toList();

        return candidates.stream()
                .filter(candidate -> isLikelyDuplicate(ticket, candidate))
                .map(candidate -> toDuplicateCandidate(ticket, candidate))
                .sorted(Comparator.comparingInt((Map<String, Object> c) -> (int) c.get("score")).reversed())
                .limit(5)
                .toList();
    }

    public Map<String, Object> mergeTickets(String userId, String primaryTicketId, String duplicateTicketId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SUPPORT_AGENT", "ADMIN");

        if (primaryTicketId.equals(duplicateTicketId)) {
            throw new RuntimeException("Cannot merge a ticket into itself");
        }

        Ticket primary = ticketRepository.findById(primaryTicketId)
                .orElseThrow(() -> new RuntimeException("Primary ticket not found"));
        Ticket duplicate = ticketRepository.findById(duplicateTicketId)
                .orElseThrow(() -> new RuntimeException("Duplicate ticket not found"));

        if (primary.getMergedIntoTicketId() != null && !primary.getMergedIntoTicketId().isBlank()) {
            throw new RuntimeException("Primary ticket has already been merged into another ticket");
        }
        if (duplicate.getMergedIntoTicketId() != null && !duplicate.getMergedIntoTicketId().isBlank()) {
            throw new RuntimeException("This ticket has already been merged");
        }
        if ("merged".equalsIgnoreCase(duplicate.getStatus())) {
            throw new RuntimeException("This ticket has already been merged");
        }
        if (!sameCustomer(primary, duplicate)) {
            throw new RuntimeException("Tickets must belong to the same customer to merge");
        }

        LocalDateTime now = LocalDateTime.now();
        List<TicketMessage> duplicateMessages = messageRepository.findByTicketIdOrderByCreatedAtAsc(duplicate.getId());
        for (TicketMessage msg : duplicateMessages) {
            msg.setTicketId(primary.getId());
            messageRepository.save(msg);
        }

        String duplicateRef = metricsService.ticketNumber(duplicate);
        saveSystemInternalNote(primary,
                "Merged ticket " + duplicateRef + " (\"" + duplicate.getSubject() + "\") into this ticket. "
                        + duplicateMessages.size() + " message(s) moved.");

        duplicate.setStatus("merged");
        duplicate.setMergedIntoTicketId(primary.getId());
        duplicate.setMergedAt(now);
        duplicate.setUpdatedAt(now);
        ticketRepository.save(duplicate);

        primary.setUpdatedAt(now);
        ticketRepository.save(primary);

        auditLogService.log(user, "TICKET_MERGE", "Ticketing",
                "Merged " + duplicateRef + " into " + metricsService.ticketNumber(primary));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("primaryTicket", primary);
        response.put("mergedTicketId", duplicate.getId());
        response.put("messagesMoved", duplicateMessages.size());
        response.put("message", "Ticket merged successfully");
        return response;
    }

    private List<Ticket> findSameCustomerTickets(Ticket ticket) {
        Set<String> seen = new HashSet<>();
        List<Ticket> results = new ArrayList<>();

        if (ticket.getCustomerId() != null && !ticket.getCustomerId().isBlank()) {
            for (Ticket t : ticketRepository.findByCustomerIdOrderByCreatedAtDesc(ticket.getCustomerId())) {
                if (seen.add(t.getId())) {
                    results.add(t);
                }
            }
        }

        if (ticket.getCustomerEmail() != null && !ticket.getCustomerEmail().isBlank()) {
            for (Ticket t : ticketRepository.findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc(ticket.getCustomerEmail().trim())) {
                if (seen.add(t.getId())) {
                    results.add(t);
                }
            }
        }

        return results;
    }

    private boolean sameCustomer(Ticket a, Ticket b) {
        if (a.getCustomerId() != null && a.getCustomerId().equals(b.getCustomerId())) {
            return true;
        }
        if (a.getCustomerEmail() != null && b.getCustomerEmail() != null) {
            return a.getCustomerEmail().equalsIgnoreCase(b.getCustomerEmail().trim());
        }
        return false;
    }

    private static final Set<String> DUPLICATE_STOP_WORDS = Set.of(
            "about", "and", "cant", "cannot", "error", "for", "help", "issue", "my", "need",
            "not", "please", "problem", "support", "the", "ticket", "unable", "with", "your"
    );

    private Map<String, Object> toDuplicateCandidate(Ticket source, Ticket candidate) {
        int subjectScore = subjectSimilarity(source.getSubject(), candidate.getSubject());
        int descriptionScore = textSimilarity(source.getDescription(), candidate.getDescription());
        int score = duplicateDisplayScore(subjectScore, descriptionScore, source, candidate);
        String confidence = duplicateConfidence(subjectScore, descriptionScore, source, candidate);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", candidate.getId());
        item.put("ticketNumber", metricsService.ticketNumber(candidate));
        item.put("subject", candidate.getSubject());
        item.put("descriptionPreview", previewText(candidate.getDescription(), 120));
        item.put("status", candidate.getStatus());
        item.put("priority", candidate.getPriority());
        item.put("category", candidate.getCategory());
        item.put("createdAt", candidate.getCreatedAt());
        item.put("messageCount", messageRepository.findByTicketIdOrderByCreatedAtAsc(candidate.getId()).size());
        item.put("subjectScore", subjectScore);
        item.put("descriptionScore", descriptionScore);
        item.put("score", score);
        item.put("confidence", confidence);
        item.put("reason", duplicateReason(source, candidate, subjectScore, descriptionScore));
        item.put("likelyDuplicate", true);
        return item;
    }

    private boolean isLikelyDuplicate(Ticket source, Ticket candidate) {
        int subjectScore = subjectSimilarity(source.getSubject(), candidate.getSubject());
        int descriptionScore = textSimilarity(source.getDescription(), candidate.getDescription());
        long hoursApart = hoursApart(source, candidate);

        if (normalizedTextEquals(source.getSubject(), candidate.getSubject())) {
            return true;
        }
        if (subjectScore >= 90) {
            return true;
        }
        if (subjectScore >= 70 && hoursApart <= 168) {
            return true;
        }
        if (subjectScore >= 55 && descriptionScore >= 55 && hoursApart <= 72) {
            return true;
        }
        if (descriptionScore >= 75 && subjectScore >= 40) {
            return true;
        }
        return subjectsOverlapStrongly(source.getSubject(), candidate.getSubject())
                && descriptionScore >= 35
                && hoursApart <= 168;
    }

    private int duplicateDisplayScore(int subjectScore, int descriptionScore, Ticket source, Ticket candidate) {
        int score = (int) Math.round((subjectScore * 0.65) + (descriptionScore * 0.35));
        if (normalizedTextEquals(source.getSubject(), candidate.getSubject())) {
            score = Math.max(score, 95);
        }
        long hoursApart = hoursApart(source, candidate);
        if (hoursApart <= 24) {
            score += 5;
        }
        return Math.min(100, score);
    }

    private String duplicateConfidence(int subjectScore, int descriptionScore, Ticket source, Ticket candidate) {
        if (normalizedTextEquals(source.getSubject(), candidate.getSubject())) {
            return "high";
        }
        if (subjectScore >= 80 || (subjectScore >= 65 && descriptionScore >= 60)) {
            return "high";
        }
        return "medium";
    }

    private String duplicateReason(Ticket source, Ticket candidate, int subjectScore, int descriptionScore) {
        List<String> reasons = new ArrayList<>();
        if (normalizedTextEquals(source.getSubject(), candidate.getSubject())) {
            reasons.add("same subject line");
        } else if (subjectScore >= 80) {
            reasons.add("very similar subject");
        } else if (subjectScore >= 55) {
            reasons.add("related subject");
        }
        if (descriptionScore >= 70) {
            reasons.add("very similar description");
        } else if (descriptionScore >= 50) {
            reasons.add("similar description");
        }
        long hoursApart = hoursApart(source, candidate);
        if (hoursApart <= 24) {
            reasons.add("submitted within 24h");
        } else if (hoursApart <= 72) {
            reasons.add("submitted within 3 days");
        }
        if (reasons.isEmpty()) {
            return "same customer";
        }
        return String.join(" · ", reasons);
    }

    private long hoursApart(Ticket source, Ticket candidate) {
        if (source.getCreatedAt() == null || candidate.getCreatedAt() == null) {
            return Long.MAX_VALUE;
        }
        return Math.abs(java.time.Duration.between(source.getCreatedAt(), candidate.getCreatedAt()).toHours());
    }

    private boolean subjectsOverlapStrongly(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String left = normalizeText(a);
        String right = normalizeText(b);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        return left.contains(right) || right.contains(left);
    }

    private boolean normalizedTextEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String left = normalizeText(a);
        String right = normalizeText(b);
        return !left.isEmpty() && left.equals(right);
    }

    private String normalizeText(String value) {
        return value.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String previewText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength - 1).trim() + "…";
    }

    private int subjectSimilarity(String a, String b) {
        return textSimilarity(a, b);
    }

    private int textSimilarity(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        String left = normalizeText(a);
        String right = normalizeText(b);
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        if (left.equals(right)) {
            return 100;
        }
        if (left.contains(right) || right.contains(left)) {
            return 85;
        }

        Set<String> leftWords = tokenizeText(left);
        Set<String> rightWords = tokenizeText(right);
        if (leftWords.isEmpty() || rightWords.isEmpty()) {
            return 0;
        }

        long overlap = leftWords.stream().filter(rightWords::contains).count();
        int union = leftWords.size() + rightWords.size() - (int) overlap;
        if (union == 0) {
            return 0;
        }
        return (int) Math.round((overlap * 100.0) / union);
    }

    private Set<String> tokenizeText(String text) {
        return Arrays.stream(text.split("\\s+"))
                .map(w -> w.replaceAll("[^a-z0-9]", ""))
                .filter(w -> w.length() > 2)
                .filter(w -> !DUPLICATE_STOP_WORDS.contains(w))
                .collect(Collectors.toSet());
    }

    private boolean isOpenLike(String status) {
        return "open".equalsIgnoreCase(status) || "in_progress".equalsIgnoreCase(status);
    }

    private boolean canAccess(User user, Ticket ticket) {
        String role = user.getRole() == null ? "" : user.getRole().toUpperCase();
        if (user.getId().equals(ticket.getCustomerId())) return true;
        if (user.getId().equals(ticket.getAssigneeId())) return true;
        return role.equals("ADMIN") || role.equals("SUPERVISOR") || role.equals("SUPPORT_AGENT");
    }

    private boolean isCustomer(User user) {
        return "CUSTOMER".equalsIgnoreCase(user.getRole());
    }

    private boolean isAdmin(User user) {
        return "ADMIN".equalsIgnoreCase(user.getRole());
    }

    private void enforceAdminReplyPolicy(User user, Ticket ticket, boolean internalNote) {
        if (!isAdmin(user) || internalNote) {
            return;
        }
        if (!ticket.isAdminTakeover() && !ticket.isSlaEscalated()) {
            throw new RuntimeException(
                    "Administrators can only add internal notes unless this ticket is SLA-escalated or you take it over");
        }
    }

    private AgentPresence pickAvailableSupportAgent() {
        return pickAvailableSupportAgentExcluding(null);
    }

    private AgentPresence pickAvailableSupportAgentExcluding(String excludeUserId) {
        List<AgentPresence> team = presenceRepository.findByTeam("support");
        if (team == null || team.isEmpty()) {
            return null;
        }

        List<AgentPresence> available = team.stream()
                .filter(p -> p.getStatus() != null && p.getStatus().equalsIgnoreCase("available"))
                .filter(p -> p.getRole() != null && p.getRole().equalsIgnoreCase("SUPPORT_AGENT"))
                .toList();

        List<AgentPresence> candidates = available.isEmpty() ? team : available;
        return candidates.stream()
                .filter(p -> p.getUserId() != null)
                .filter(p -> excludeUserId == null || !excludeUserId.equals(p.getUserId()))
                .min(Comparator.comparingInt(p -> openTicketsCount(p.getUserId())))
                .orElse(null);
    }

    private void escalateBreachedTicket(Ticket ticket, LocalDateTime now) {
        String previousAssigneeId = ticket.getAssigneeId();
        String previousAssigneeName = ticket.getAssigneeName();
        AgentPresence newAgent = previousAssigneeId == null
                ? pickAvailableSupportAgent()
                : pickAvailableSupportAgentExcluding(previousAssigneeId);

        if (newAgent != null) {
            User agentUser = userRepository.findById(newAgent.getUserId()).orElse(null);
            ticket.setAssigneeId(newAgent.getUserId());
            ticket.setAssigneeName(newAgent.getFullName());
            ticket.setAssigneeAvatarUrl(agentUser != null ? agentUser.getAvatarUrl() : null);
            ticket.setStatus("in_progress");

            String note = previousAssigneeId == null
                    ? "SLA breached — automatically assigned to " + newAgent.getFullName() + "."
                    : "SLA breached — reassigned from "
                    + (previousAssigneeName != null ? previousAssigneeName : "previous agent")
                    + " to " + newAgent.getFullName() + ".";
            saveSystemInternalNote(ticket, note);

            notificationService.create(
                    newAgent.getUserId(),
                    "SLA escalation — ticket assigned",
                    "\"" + ticket.getSubject() + "\" was assigned to you due to SLA breach.",
                    "warning"
            );

            if (previousAssigneeId != null && !previousAssigneeId.equals(newAgent.getUserId())) {
                notificationService.create(
                        previousAssigneeId,
                        "SLA escalation — ticket reassigned",
                        "\"" + ticket.getSubject() + "\" was reassigned due to SLA breach.",
                        "info"
                );
            }
        } else {
            if (previousAssigneeId != null) {
                notificationService.create(
                        previousAssigneeId,
                        "SLA breached",
                        "Ticket \"" + ticket.getSubject() + "\" exceeded its SLA. No agents available for reassignment.",
                        "warning"
                );
            }
            notificationService.broadcastToAudience(
                    "SLA breached",
                    "Ticket \"" + ticket.getSubject() + "\" exceeded its SLA"
                            + (previousAssigneeId == null ? " and is unassigned." : " — reassignment unavailable."),
                    "supervisors"
            );
        }

        ticket.setSlaEscalated(true);
        ticket.setSlaEscalatedAt(now);
        ticket.setUpdatedAt(now);
        ticketRepository.save(ticket);
    }

    private void saveSystemInternalNote(Ticket ticket, String message) {
        TicketMessage entry = new TicketMessage();
        entry.setTicketId(ticket.getId());
        entry.setAuthorName("System");
        entry.setMessage(message);
        entry.setInternalNote(true);
        entry.setCreatedAt(LocalDateTime.now());
        messageRepository.save(entry);
    }

    private int openTicketsCount(String assigneeId) {
        if (assigneeId == null) return Integer.MAX_VALUE;
        return (int) ticketRepository.countByAssigneeIdAndStatusIn(
                assigneeId, List.of("open", "in_progress"));
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

    private void notifySupportAgentsOfNewTicket(Ticket ticket) {
        userRepository.findAll().stream()
                .filter(u -> "SUPPORT_AGENT".equalsIgnoreCase(u.getRole()))
                .findFirst()
                .ifPresent(agent -> notificationService.create(agent.getId(), "New support ticket",
                        ticket.getCustomerName() + ": " + ticket.getSubject(), "info"));
    }

    private Ticket requireValidGuestTicket(String token) {
        if (token == null || token.isBlank()) {
            throw new RuntimeException("Invalid ticket link");
        }
        Ticket ticket = ticketRepository.findByGuestAccessToken(token)
                .orElseThrow(() -> new RuntimeException("Ticket not found or link has expired"));
        if (ticket.getGuestTokenExpiresAt() != null && ticket.getGuestTokenExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("This ticket link has expired");
        }
        return ticket;
    }

    private String guestTicketPortalUrl(Ticket ticket) {
        return FRONTEND_URL + "/support/portal/" + ticket.getGuestAccessToken();
    }

    private Map<String, Object> toGuestTicketView(Ticket ticket) {
        Map<String, Object> view = new HashMap<>();
        view.put("id", ticket.getId());
        view.put("subject", ticket.getSubject());
        view.put("description", ticket.getDescription());
        view.put("status", ticket.getStatus());
        view.put("priority", ticket.getPriority());
        view.put("category", ticket.getCategory());
        view.put("customerName", ticket.getCustomerName());
        view.put("assigneeName", ticket.getAssigneeName());
        view.put("attachmentUrl", ticket.getAttachmentUrl());
        view.put("createdAt", ticket.getCreatedAt());
        view.put("updatedAt", ticket.getUpdatedAt());
        return view;
    }

    private String stringVal(String value) {
        return value == null ? "" : value.trim();
    }

    private Map<String, String> macro(String id, String label, String content) {
        return Map.of(
                "id", id,
                "label", label,
                "content", content
        );
    }
}
