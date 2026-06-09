package com.cyforce.config;

import com.cyforce.model.*;
import com.cyforce.repository.*;
import com.cyforce.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Order(2)
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final TicketRepository ticketRepository;
    private final TicketMessageRepository messageRepository;
    private final LeadRepository leadRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final KnowledgeArticleRepository articleRepository;
    private final TicketFeedbackRepository feedbackRepository;
    private final AgentPresenceRepository presenceRepository;
    private final InvoiceRepository invoiceRepository;

    public DataSeeder(TicketRepository ticketRepository,
                      TicketMessageRepository messageRepository,
                      LeadRepository leadRepository,
                      AuditLogRepository auditLogRepository,
                      UserRepository userRepository,
                      NotificationService notificationService,
                      KnowledgeArticleRepository articleRepository,
                      TicketFeedbackRepository feedbackRepository,
                      AgentPresenceRepository presenceRepository,
                      InvoiceRepository invoiceRepository) {
        this.ticketRepository = ticketRepository;
        this.messageRepository = messageRepository;
        this.leadRepository = leadRepository;
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.articleRepository = articleRepository;
        this.feedbackRepository = feedbackRepository;
        this.presenceRepository = presenceRepository;
        this.invoiceRepository = invoiceRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (ticketRepository.count() > 0) {
            seedSupplementaryData();
            return;
        }

        log.info("Seeding demo CRM data...");

        User salesOwner = userRepository.findAll().stream()
                .filter(u -> "SALES_AGENT".equalsIgnoreCase(u.getRole()))
                .findFirst()
                .orElse(null);
        User supportAgent = userRepository.findAll().stream()
                .filter(u -> "SUPPORT_AGENT".equalsIgnoreCase(u.getRole()))
                .findFirst()
                .orElse(null);
        User customer = userRepository.findAll().stream()
                .filter(u -> "CUSTOMER".equalsIgnoreCase(u.getRole()))
                .findFirst()
                .orElse(null);

        String ownerId = salesOwner != null ? salesOwner.getId() : null;
        String ownerName = salesOwner != null ? salesOwner.getFullName() : "Unassigned";
        String agentId = supportAgent != null ? supportAgent.getId() : null;
        String agentName = supportAgent != null ? supportAgent.getFullName() : "Support Agent";

        leadRepository.saveAll(List.of(
                new Lead(null, "John Smith", "john.smith@example.com", "+2348011111111", "Acme Corp", "website", "new", 72, ownerId, ownerName, LocalDateTime.now().minusDays(2), LocalDateTime.now()),
                new Lead(null, "Sarah Johnson", "sarah.j@example.com", "+2348022222222", "TechStart", "referral", "qualified", 85, ownerId, ownerName, LocalDateTime.now().minusDays(5), LocalDateTime.now()),
                new Lead(null, "Ibrahim Musa", "ibrahim@solar.ng", "+2348033333333", "Solar NG", "event", "converted", 90, ownerId, ownerName, LocalDateTime.now().minusDays(10), LocalDateTime.now().minusDays(1))
        ));

        Ticket t1 = ticketRepository.save(new Ticket(null, customer != null ? customer.getId() : null, "Sarah Thompson", "sarah@techinn.com", "Login Issues", "Cannot access account after password reset", null, "Technical", "high", "in_progress", agentId, agentName, LocalDateTime.now().minusHours(3), LocalDateTime.now().minusMinutes(20)));
        Ticket t2 = ticketRepository.save(new Ticket(null, null, "Michael Chen", "michael@acme.com", "Billing discrepancy", "Invoice amount does not match subscription", null, "Billing", "medium", "open", null, null, LocalDateTime.now().minusHours(6), LocalDateTime.now().minusHours(6)));
        Ticket t3 = ticketRepository.save(new Ticket(null, null, "Lisa Park", "lisa@global.com", "API integration help", "Need help connecting REST API", null, "Technical", "low", "resolved", agentId, agentName, LocalDateTime.now().minusDays(1), LocalDateTime.now().minusHours(2)));
        Ticket t4 = ticketRepository.save(new Ticket(null, null, "David Okon", "david@startup.ng", "Account locked", "Multiple failed login attempts", null, "Security", "high", "open", agentId, agentName, LocalDateTime.now().minusHours(5), LocalDateTime.now().minusHours(1)));

        if (agentId != null) {
            messageRepository.save(new TicketMessage(null, t1.getId(), agentId, agentName, "Hi Sarah, we're looking into your login issue now.", false, LocalDateTime.now().minusHours(2)));
            messageRepository.save(new TicketMessage(null, t3.getId(), agentId, agentName, "API docs sent. Let us know if you need more help.", false, LocalDateTime.now().minusHours(3)));
        }

        auditLogRepository.saveAll(List.of(
                new AuditLog(null, null, "admin@cyforce.com", "USER_UPDATE", "User Management", "Updated user profile", LocalDateTime.now().minusMinutes(5)),
                new AuditLog(null, null, "supervisor@cyforce.com", "ROLE_ASSIGN", "Roles & Permissions", "Assigned sales agent role", LocalDateTime.now().minusMinutes(15)),
                new AuditLog(null, null, "support@cyforce.com", "TICKET_CREATE", "Ticketing", "Created ticket for billing issue", LocalDateTime.now().minusHours(1))
        ));

        articleRepository.saveAll(List.of(
                new KnowledgeArticle(null, "How to reset your password", "Account", "Step-by-step password reset guide", "password,account", 120, true, LocalDateTime.now().minusDays(30)),
                new KnowledgeArticle(null, "Understanding your invoice", "Billing", "Invoice line items explained", "billing,invoice", 85, true, LocalDateTime.now().minusDays(20)),
                new KnowledgeArticle(null, "Creating a support ticket", "Support", "How to open and track tickets", "support,ticket", 200, true, LocalDateTime.now().minusDays(15)),
                new KnowledgeArticle(null, "API authentication guide", "Technical", "OAuth and API key setup", "api,technical", 64, true, LocalDateTime.now().minusDays(10))
        ));

        if (agentId != null) {
            feedbackRepository.saveAll(List.of(
                    new TicketFeedback(null, t3.getId(), null, "Lisa Park", "Global Solutions", 5, "Very fast and helpful response!", agentId, LocalDateTime.now().minusHours(1)),
                    new TicketFeedback(null, t1.getId(), null, "Sarah Thompson", "TechInn", 4, "Good support, waiting on resolution.", agentId, LocalDateTime.now().minusMinutes(30))
            ));
        }

        userRepository.findAll().stream()
                .filter(u -> "SUPPORT_AGENT".equalsIgnoreCase(u.getRole()) || "SALES_AGENT".equalsIgnoreCase(u.getRole()) || "SUPERVISOR".equalsIgnoreCase(u.getRole()))
                .forEach(u -> {
                    AgentPresence p = new AgentPresence();
                    p.setUserId(u.getId());
                    p.setFullName(u.getFullName());
                    p.setRole(u.getRole());
                    p.setTeam("SALES_AGENT".equalsIgnoreCase(u.getRole()) ? "sales" : "support");
                    p.setStatus("SUPPORT_AGENT".equalsIgnoreCase(u.getRole()) ? "available" : "busy");
                    p.setStatusSince(LocalDateTime.now().minusMinutes(45));
                    p.setShiftLabel("Morning Shift · 8:00 AM – 4:00 PM");
                    p.setUpdatedAt(LocalDateTime.now());
                    presenceRepository.save(p);
                });

        if (customer != null) {
            invoiceRepository.saveAll(List.of(
                    new Invoice(null, customer.getId(), customer.getFullName(), 4990000, "NGN", "pending", "Enterprise Plan - March 2026", LocalDateTime.now().plusDays(14), null, null, LocalDateTime.now().minusDays(5)),
                    new Invoice(null, customer.getId(), customer.getFullName(), 1990000, "NGN", "paid", "Professional Plan - February 2026", LocalDateTime.now().minusDays(10), null, LocalDateTime.now().minusDays(12), LocalDateTime.now().minusDays(30))
            ));
        }

        userRepository.findByEmailIgnoreCase("lovethdurodoye@gmail.com").ifPresent(admin ->
                notificationService.create(admin.getId(), "Welcome to CyForce CRM",
                        "Your admin dashboard is ready. Metrics and alerts are driven by live system data.", "info")
        );

        log.info("Demo CRM data seeded");
    }

    private void seedSupplementaryData() {
        if (articleRepository.count() == 0) {
            articleRepository.saveAll(List.of(
                    new KnowledgeArticle(null, "How to reset your password", "Account", "Step-by-step password reset guide", "password,account", 120, true, LocalDateTime.now().minusDays(30)),
                    new KnowledgeArticle(null, "Understanding your invoice", "Billing", "Invoice line items explained", "billing,invoice", 85, true, LocalDateTime.now().minusDays(20))
            ));
        }
        if (presenceRepository.count() == 0) {
            userRepository.findAll().stream()
                    .filter(u -> "SUPPORT_AGENT".equalsIgnoreCase(u.getRole()) || "SALES_AGENT".equalsIgnoreCase(u.getRole()))
                    .forEach(u -> {
                        AgentPresence p = new AgentPresence();
                        p.setUserId(u.getId());
                        p.setFullName(u.getFullName());
                        p.setRole(u.getRole());
                        p.setTeam("SALES_AGENT".equalsIgnoreCase(u.getRole()) ? "sales" : "support");
                        p.setStatus("available");
                        p.setStatusSince(LocalDateTime.now().minusMinutes(30));
                        p.setShiftLabel("Morning Shift · 8:00 AM – 4:00 PM");
                        p.setUpdatedAt(LocalDateTime.now());
                        presenceRepository.save(p);
                    });
        }
    }
}
