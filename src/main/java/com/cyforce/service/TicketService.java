package com.cyforce.service;

import com.cyforce.model.Ticket;
import com.cyforce.model.TicketMessage;
import com.cyforce.model.User;
import com.cyforce.repository.TicketMessageRepository;
import com.cyforce.repository.TicketRepository;
import com.cyforce.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final TicketMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final RequestUserService requestUserService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final FileStorageService fileStorageService;

    public TicketService(TicketRepository ticketRepository,
                         TicketMessageRepository messageRepository,
                         UserRepository userRepository,
                         RequestUserService requestUserService,
                         NotificationService notificationService,
                         AuditLogService auditLogService,
                         FileStorageService fileStorageService) {
        this.ticketRepository = ticketRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.requestUserService = requestUserService;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.fileStorageService = fileStorageService;
    }

    public List<Ticket> customerTickets(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "CUSTOMER");
        return ticketRepository.findByCustomerIdOrderByCreatedAtDesc(user.getId());
    }

    public List<Ticket> supportTickets(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SUPPORT_AGENT", "ADMIN", "SUPERVISOR");
        return ticketRepository.findByAssigneeIdOrderByCreatedAtDesc(user.getId());
    }

    public List<Ticket> allOpenTickets(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SUPPORT_AGENT", "ADMIN", "SUPERVISOR");
        return ticketRepository.findByStatusInOrderByCreatedAtDesc(List.of("open", "in_progress"));
    }

    public List<Ticket> allTickets(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "ADMIN", "SUPERVISOR");
        return ticketRepository.findAllByOrderByCreatedAtDesc();
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
        ticket.setStatus("open");
        if (attachment != null && !attachment.isEmpty()) {
            ticket.setAttachmentUrl(fileStorageService.storeTicketAttachment(attachment));
        }
        ticket.setCreatedAt(LocalDateTime.now());
        ticket.setUpdatedAt(LocalDateTime.now());
        Ticket saved = ticketRepository.save(ticket);
        auditLogService.log(user, "TICKET_CREATE", "Ticketing", saved.getSubject());

        notificationService.create(user.getId(), "Ticket created",
                "Your support ticket \"" + saved.getSubject() + "\" has been submitted.", "info");

        userRepository.findAll().stream()
                .filter(u -> "SUPPORT_AGENT".equalsIgnoreCase(u.getRole()))
                .findFirst()
                .ifPresent(agent -> notificationService.create(agent.getId(), "New support ticket",
                        user.getFullName() + ": " + saved.getSubject(), "info"));

        return saved;
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
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));
        ticket.setAssigneeId(user.getId());
        ticket.setAssigneeName(user.getFullName());
        ticket.setStatus("in_progress");
        ticket.setUpdatedAt(LocalDateTime.now());
        return ticketRepository.save(ticket);
    }

    public Ticket updateStatus(String userId, String ticketId, String status) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SUPPORT_AGENT", "ADMIN", "SUPERVISOR");
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));
        ticket.setStatus(status);
        ticket.setUpdatedAt(LocalDateTime.now());
        Ticket saved = ticketRepository.save(ticket);
        if (ticket.getCustomerId() != null) {
            notificationService.create(ticket.getCustomerId(), "Ticket updated",
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
        }

        return saveMessage(user, ticket, message, internalNote);
    }

    private TicketMessage saveMessage(User user, Ticket ticket, String message, boolean internalNote) {
        TicketMessage entry = new TicketMessage();
        entry.setTicketId(ticket.getId());
        entry.setAuthorId(user.getId());
        entry.setAuthorName(user.getFullName());
        entry.setMessage(message);
        entry.setInternalNote(internalNote);
        entry.setCreatedAt(LocalDateTime.now());
        ticket.setUpdatedAt(LocalDateTime.now());
        ticketRepository.save(ticket);

        if (!internalNote && ticket.getCustomerId() != null && !user.getId().equals(ticket.getCustomerId())) {
            notificationService.create(ticket.getCustomerId(), "New ticket response",
                    "Support replied on \"" + ticket.getSubject() + "\"", "info");
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
        List<Ticket> mine = supportTickets(userId);
        long resolved = mine.stream().filter(t -> "resolved".equals(t.getStatus()) || "closed".equals(t.getStatus())).count();
        return Map.of("assignedTickets", mine.size(), "resolvedTickets", resolved, "openQueue", allOpenTickets(userId).size());
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
}
