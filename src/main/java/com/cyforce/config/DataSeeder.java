package com.cyforce.config;

import com.cyforce.model.*;
import com.cyforce.repository.*;
import com.cyforce.service.NotificationService;
import com.cyforce.model.Product;
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
    private final HotDealRepository hotDealRepository;
    private final ProductRepository productRepository;

    public DataSeeder(TicketRepository ticketRepository,
                      TicketMessageRepository messageRepository,
                      LeadRepository leadRepository,
                      AuditLogRepository auditLogRepository,
                      UserRepository userRepository,
                      NotificationService notificationService,
                      KnowledgeArticleRepository articleRepository,
                      TicketFeedbackRepository feedbackRepository,
                      AgentPresenceRepository presenceRepository,
                      InvoiceRepository invoiceRepository,
                      HotDealRepository hotDealRepository,
                      ProductRepository productRepository) {
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
        this.hotDealRepository = hotDealRepository;
        this.productRepository = productRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (ticketRepository.count() > 0) {
            seedSupplementaryData();
            return;
        }

        log.info("Seeding demo CRM data...");

        User salesOwner = userRepository.findByEmailIgnoreCase("groupfcmp@gmail.com")
                .filter(u -> "SALES_AGENT".equalsIgnoreCase(u.getRole()))
                .orElseGet(() -> userRepository.findAll().stream()
                        .filter(u -> "SALES_AGENT".equalsIgnoreCase(u.getRole()))
                        .findFirst()
                        .orElse(null));
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
                demoLead("John Smith", "john.smith@example.com", "+2348011111111", "Acme Corp", "website", "new", 72, ownerId, ownerName, LocalDateTime.now().minusDays(2), LocalDateTime.now()),
                demoLead("Sarah Johnson", "sarah.j@example.com", "+2348022222222", "TechStart", "referral", "qualified", 85, ownerId, ownerName, LocalDateTime.now().minusDays(5), LocalDateTime.now()),
                demoLead("Ibrahim Musa", "ibrahim@solar.ng", "+2348033333333", "Solar NG", "event", "converted", 90, ownerId, ownerName, LocalDateTime.now().minusDays(10), LocalDateTime.now().minusDays(1))
        ));

        Ticket t1 = ticketRepository.save(demoTicket(
                customer != null ? customer.getId() : null, "Sarah Thompson", "sarah@techinn.com",
                "Login Issues", "Cannot access account after password reset", "Technical", "high", "in_progress",
                agentId, agentName, LocalDateTime.now().minusHours(3), LocalDateTime.now().minusMinutes(20)));
        Ticket t2 = ticketRepository.save(demoTicket(
                null, "Michael Chen", "michael@acme.com",
                "Billing discrepancy", "Invoice amount does not match subscription", "Billing", "medium", "open",
                null, null, LocalDateTime.now().minusHours(6), LocalDateTime.now().minusHours(6)));
        Ticket t3 = ticketRepository.save(demoTicket(
                null, "Lisa Park", "lisa@global.com",
                "API integration help", "Need help connecting REST API", "Technical", "low", "resolved",
                agentId, agentName, LocalDateTime.now().minusDays(1), LocalDateTime.now().minusHours(2)));
        Ticket t4 = ticketRepository.save(demoTicket(
                null, "David Okon", "david@startup.ng",
                "Account locked", "Multiple failed login attempts", "Security", "high", "open",
                agentId, agentName, LocalDateTime.now().minusHours(5), LocalDateTime.now().minusHours(1)));

        if (agentId != null) {
            messageRepository.save(demoMessage(t1.getId(), agentId, agentName, "Hi Sarah, we're looking into your login issue now.", LocalDateTime.now().minusHours(2)));
            messageRepository.save(demoMessage(t3.getId(), agentId, agentName, "API docs sent. Let us know if you need more help.", LocalDateTime.now().minusHours(3)));
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
                    demoInvoice(customer.getId(), customer.getFullName(), 4990000, "pending",
                            "Enterprise Plan - March 2026", LocalDateTime.now().plusDays(14), null, LocalDateTime.now().minusDays(5)),
                    demoInvoice(customer.getId(), customer.getFullName(), 1990000, "paid",
                            "Professional Plan - February 2026", LocalDateTime.now().minusDays(10), LocalDateTime.now().minusDays(12), LocalDateTime.now().minusDays(30))
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
        ensureDemoLeadsForSalesAgents();
        seedHotDealsIfEmpty();
    }

    private void seedHotDealsIfEmpty() {
        boolean hasActive = hotDealRepository.findAll().stream().anyMatch(HotDeal::isActive);
        if (hasActive) {
            return;
        }
        Product linkedProduct = productRepository.findAll().stream()
                .filter(Product::isActive)
                .findFirst()
                .orElse(null);
        HotDeal deal = new HotDeal();
        deal.setTitle(linkedProduct != null ? linkedProduct.getName() + " — Hot Deal" : "20% Off CCTV Installation");
        deal.setDescription(linkedProduct != null
                ? linkedProduct.getDescription()
                : "Professional CCTV packages at a limited-time discount for CyForce customers.");
        deal.setBadge("Hot Deal");
        deal.setPrice(linkedProduct != null ? Math.max(1, linkedProduct.getPrice() - 50000) : 150000L);
        deal.setOriginalPrice(linkedProduct != null ? linkedProduct.getPrice() : 200000L);
        deal.setDiscountPercent(25);
        if (linkedProduct != null) {
            deal.setProductId(linkedProduct.getId());
        }
        deal.setImageUrl("https://images.unsplash.com/photo-1563013544-824ae1b704d3?w=800&q=80");
        deal.setCtaLabel("Shop now");
        deal.setCtaLink("/customer/products");
        deal.setActive(true);
        deal.setCreatedByName("CyForce");
        deal.setCreatedAt(LocalDateTime.now());
        deal.setUpdatedAt(LocalDateTime.now());
        hotDealRepository.save(deal);
        log.info("Seeded default hot deal for customer portal");
    }

    private void ensureDemoLeadsForSalesAgents() {
        userRepository.findAll().stream()
                .filter(u -> "SALES_AGENT".equalsIgnoreCase(u.getRole()))
                .forEach(agent -> {
                    if (!leadRepository.findByOwnerIdOrderByCreatedAtDesc(agent.getId()).isEmpty()) {
                        return;
                    }
                    log.info("Seeding demo leads for sales agent {}", agent.getEmail());
                    leadRepository.saveAll(List.of(
                            demoLead("John Smith", "john.smith@example.com", "+2348011111111", "Acme Corp", "website", "new", 72, agent.getId(), agent.getFullName(), LocalDateTime.now().minusDays(2), LocalDateTime.now()),
                            demoLead("Sarah Johnson", "sarah.j@example.com", "+2348022222222", "TechStart", "referral", "qualified", 85, agent.getId(), agent.getFullName(), LocalDateTime.now().minusDays(5), LocalDateTime.now()),
                            demoLead("Ibrahim Musa", "ibrahim@solar.ng", "+2348033333333", "Solar NG", "event", "converted", 90, agent.getId(), agent.getFullName(), LocalDateTime.now().minusDays(10), LocalDateTime.now().minusDays(1))
                    ));
                });
    }

    private static Lead demoLead(String name, String email, String phone, String company, String source,
                                 String status, int score, String ownerId, String ownerName,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) {
        Lead lead = new Lead();
        lead.setName(name);
        lead.setEmail(email);
        lead.setPhone(phone);
        lead.setCompany(company);
        lead.setSource(source);
        lead.setStatus(status);
        lead.setScore(score);
        lead.setOwnerId(ownerId);
        lead.setOwnerName(ownerName);
        lead.setCreatedAt(createdAt);
        lead.setUpdatedAt(updatedAt);
        return lead;
    }

    private static Ticket demoTicket(String customerId, String customerName, String customerEmail,
                                     String subject, String description, String category,
                                     String priority, String status, String assigneeId, String assigneeName,
                                     LocalDateTime createdAt, LocalDateTime updatedAt) {
        Ticket ticket = new Ticket();
        ticket.setCustomerId(customerId);
        ticket.setCustomerName(customerName);
        ticket.setCustomerEmail(customerEmail);
        ticket.setSubject(subject);
        ticket.setDescription(description);
        ticket.setCategory(category);
        ticket.setPriority(priority);
        ticket.setStatus(status);
        ticket.setAssigneeId(assigneeId);
        ticket.setAssigneeName(assigneeName);
        ticket.setCreatedAt(createdAt);
        ticket.setUpdatedAt(updatedAt);
        return ticket;
    }

    private static TicketMessage demoMessage(String ticketId, String authorId, String authorName,
                                             String message, LocalDateTime createdAt) {
        TicketMessage entry = new TicketMessage();
        entry.setTicketId(ticketId);
        entry.setAuthorId(authorId);
        entry.setAuthorName(authorName);
        entry.setMessage(message);
        entry.setInternalNote(false);
        entry.setCreatedAt(createdAt);
        return entry;
    }

    private static Invoice demoInvoice(String customerId, String customerName, long amount, String status,
                                       String description, LocalDateTime dueDate,
                                       LocalDateTime paidAt, LocalDateTime createdAt) {
        Invoice invoice = new Invoice();
        invoice.setCustomerId(customerId);
        invoice.setCustomerName(customerName);
        invoice.setAmount(amount);
        invoice.setCurrency("NGN");
        invoice.setStatus(status);
        invoice.setDescription(description);
        invoice.setDueDate(dueDate);
        invoice.setPaidAt(paidAt);
        invoice.setCreatedAt(createdAt);
        return invoice;
    }
}
