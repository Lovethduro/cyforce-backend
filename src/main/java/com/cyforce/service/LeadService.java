package com.cyforce.service;

import com.cyforce.model.AgentPresence;
import com.cyforce.model.Conversation;
import com.cyforce.model.Lead;
import com.cyforce.model.Product;
import com.cyforce.model.User;
import com.cyforce.repository.AgentPresenceRepository;
import com.cyforce.repository.LeadRepository;
import com.cyforce.repository.ProductRepository;
import com.cyforce.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class LeadService {

    private static final Set<String> QUOTE_TYPES = Set.of(
            "products_only",
            "products_installation",
            "installation_only"
    );

    private final LeadRepository leadRepository;
    private final RequestUserService requestUserService;
    private final AuditLogService auditLogService;
    private final UserRepository userRepository;
    private final AgentPresenceRepository agentPresenceRepository;
    private final NotificationService notificationService;
    private final ProductRepository productRepository;
    private final MessagingService messagingService;
    private final EmailService emailService;

    public LeadService(LeadRepository leadRepository,
                       RequestUserService requestUserService,
                       AuditLogService auditLogService,
                       UserRepository userRepository,
                       AgentPresenceRepository agentPresenceRepository,
                       NotificationService notificationService,
                       ProductRepository productRepository,
                       MessagingService messagingService,
                       EmailService emailService) {
        this.leadRepository = leadRepository;
        this.requestUserService = requestUserService;
        this.auditLogService = auditLogService;
        this.userRepository = userRepository;
        this.agentPresenceRepository = agentPresenceRepository;
        this.notificationService = notificationService;
        this.productRepository = productRepository;
        this.messagingService = messagingService;
        this.emailService = emailService;
    }

    public List<Lead> myLeads(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SALES_AGENT", "ADMIN", "SUPERVISOR");
        return leadRepository.findByOwnerIdOrderByCreatedAtDesc(user.getId());
    }

    public List<Lead> allLeads(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "ADMIN", "SUPERVISOR");
        return leadRepository.findAllByOrderByCreatedAtDesc();
    }

    public Map<String, Object> createPublicQuoteRequest(Map<String, Object> body) {
        String name = stringVal(body.get("name"));
        String email = stringVal(body.get("email")).toLowerCase();
        String phone = stringVal(body.get("phone"));
        String quoteType = stringVal(body.get("quoteType")).toLowerCase();
        String productId = stringVal(body.get("productId"));
        String productType = stringVal(body.get("productType"));
        String deliveryAddress = stringVal(body.get("deliveryAddress"));
        String installationAddress = stringVal(body.get("installationAddress"));
        String preferredInstallationDate = stringVal(body.get("preferredInstallationDate"));
        String siteContactName = stringVal(body.get("siteContactName"));
        String siteContactPhone = stringVal(body.get("siteContactPhone"));
        String existingProductDetails = stringVal(body.get("existingProductDetails"));
        int quantity = parseQuantity(body.get("quantity"));

        if (name.isBlank()) {
            throw new RuntimeException("Name is required");
        }
        if (email.isBlank() || !email.contains("@")) {
            throw new RuntimeException("A valid email is required");
        }
        if (phone.isBlank()) {
            throw new RuntimeException("Phone number is required");
        }
        phone = requireInternationalPhone(phone, "Phone number must include country code (e.g. +2348012345678)");
        if (!QUOTE_TYPES.contains(quoteType)) {
            throw new RuntimeException("Invalid quote type");
        }

        String productName = null;
        switch (quoteType) {
            case "products_only" -> {
                Product product = requireActiveProduct(productId);
                productName = product.getName();
                requireQuoteQuantity(quantity);
                if (deliveryAddress.isBlank()) {
                    throw new RuntimeException("Delivery address is required");
                }
            }
            case "products_installation" -> {
                Product product = requireActiveProduct(productId);
                productName = product.getName();
                requireQuoteQuantity(quantity);
                if (installationAddress.isBlank()) {
                    throw new RuntimeException("Installation address is required");
                }
                if (preferredInstallationDate.isBlank()) {
                    throw new RuntimeException("Preferred installation date is required");
                }
                if (siteContactName.isBlank()) {
                    throw new RuntimeException("Site contact person is required");
                }
                if (siteContactPhone.isBlank()) {
                    throw new RuntimeException("Site contact phone is required");
                }
                siteContactPhone = requireInternationalPhone(siteContactPhone, "Site contact phone must include country code");
            }
            case "installation_only" -> {
                if (productType.isBlank()) {
                    throw new RuntimeException("Product type is required");
                }
                if (installationAddress.isBlank()) {
                    throw new RuntimeException("Installation address is required");
                }
                if (preferredInstallationDate.isBlank()) {
                    throw new RuntimeException("Preferred installation date is required");
                }
                if (existingProductDetails.isBlank()) {
                    throw new RuntimeException("Existing product details are required");
                }
                if (siteContactName.isBlank()) {
                    throw new RuntimeException("Site contact person is required");
                }
                if (siteContactPhone.isBlank()) {
                    throw new RuntimeException("Site contact phone is required");
                }
                siteContactPhone = requireInternationalPhone(siteContactPhone, "Site contact phone must include country code");
            }
            default -> throw new RuntimeException("Invalid quote type");
        }

        User agent = pickAvailableSalesAgent();
        Lead lead = new Lead();
        lead.setName(name.trim());
        lead.setEmail(email.trim());
        lead.setPhone(phone.trim());
        lead.setSource("quote_request");
        lead.setQuoteType(quoteType);
        lead.setProductId(productId.isBlank() ? null : productId);
        lead.setProductName(productName);
        lead.setQuantity(quantity > 0 ? quantity : null);
        lead.setDeliveryAddress(deliveryAddress.isBlank() ? null : deliveryAddress.trim());
        lead.setInstallationAddress(installationAddress.isBlank() ? null : installationAddress.trim());
        lead.setPreferredInstallationDate(preferredInstallationDate.isBlank() ? null : preferredInstallationDate.trim());
        lead.setSiteContactName(siteContactName.isBlank() ? null : siteContactName.trim());
        lead.setSiteContactPhone(siteContactPhone.isBlank() ? null : siteContactPhone.trim());
        lead.setProductType(productType.isBlank() ? null : productType.trim());
        lead.setExistingProductDetails(existingProductDetails.isBlank() ? null : existingProductDetails.trim());
        lead.setDetails(buildQuoteDetailsSummary(lead));
        lead.setStatus("new");
        lead.setScore(65);
        lead.setOwnerId(agent.getId());
        lead.setOwnerName(agent.getFullName());
        lead.setCreatedAt(LocalDateTime.now());
        lead.setUpdatedAt(LocalDateTime.now());

        Lead saved = leadRepository.save(lead);

        Conversation conversation = messagingService.createQuoteConversation(saved, agent);
        saved.setConversationId(conversation.getId());
        saved = leadRepository.save(saved);

        notificationService.create(
                agent.getId(),
                "New quote request",
                name + " requested a quote for " + formatQuoteType(quoteType) + ". Reply in Customer Messages or send email from Leads.",
                "info"
        );

        String portalUrl = messagingService.guestPortalUrl(conversation);
        try {
            emailService.sendQuoteConfirmationEmail(
                    saved.getEmail(),
                    saved.getName(),
                    agent.getFullName(),
                    formatQuoteType(quoteType),
                    portalUrl
            );
        } catch (RuntimeException e) {
            System.err.println("Failed to send quote confirmation email: " + e.getMessage());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Thank you! Chat with your sales agent below — we also emailed you a backup link.");
        response.put("leadId", saved.getId());
        response.put("assignedAgent", agent.getFullName());
        response.put("conversationId", conversation.getId());
        response.put("portalUrl", portalUrl);
        response.put("portalToken", conversation.getGuestAccessToken());
        return response;
    }

    public Map<String, Object> sendLeadEmail(String userId, String leadId, String subject, String body) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SALES_AGENT", "ADMIN", "SUPERVISOR");

        Lead lead = leadRepository.findById(leadId).orElseThrow(() -> new RuntimeException("Lead not found"));
        if ("SALES_AGENT".equalsIgnoreCase(user.getRole())
                && lead.getOwnerId() != null
                && !user.getId().equals(lead.getOwnerId())) {
            throw new RuntimeException("Lead not found");
        }
        if (lead.getEmail() == null || lead.getEmail().isBlank()) {
            throw new RuntimeException("This lead has no email address");
        }
        if (body == null || body.isBlank()) {
            throw new RuntimeException("Email message is required");
        }

        Conversation conversation = lead.getConversationId() != null
                ? messagingService.findConversationForLead(lead.getId())
                : null;
        if (conversation == null && lead.getConversationId() != null) {
            throw new RuntimeException("Linked conversation not found");
        }

        String portalUrl = conversation != null ? messagingService.guestPortalUrl(conversation) : null;
        emailService.sendLeadOutreachEmail(
                lead.getEmail(),
                lead.getName(),
                user.getFullName(),
                subject,
                body,
                portalUrl
        );

        if (conversation != null) {
            messagingService.appendAgentEmailToConversation(user, conversation, subject, body);
        }

        if ("SALES_AGENT".equalsIgnoreCase(user.getRole()) && "new".equalsIgnoreCase(lead.getStatus())) {
            lead.setStatus("contacted");
            lead.setUpdatedAt(LocalDateTime.now());
            leadRepository.save(lead);
        }

        auditLogService.log(user, "LEAD_EMAIL", "Lead Management", lead.getName());
        return Map.of("message", "Email sent to " + lead.getEmail());
    }

    public Lead createLead(String userId, Map<String, Object> body) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SALES_AGENT", "ADMIN", "SUPERVISOR");
        Lead lead = new Lead();
        lead.setName((String) body.get("name"));
        lead.setEmail((String) body.get("email"));
        lead.setPhone((String) body.get("phone"));
        lead.setCompany((String) body.get("company"));
        lead.setSource((String) body.getOrDefault("source", "website"));
        lead.setStatus("new");
        lead.setScore(body.get("score") instanceof Number ? ((Number) body.get("score")).intValue() : 50);
        lead.setOwnerId(user.getId());
        lead.setOwnerName(user.getFullName());
        lead.setCreatedAt(LocalDateTime.now());
        lead.setUpdatedAt(LocalDateTime.now());
        Lead saved = leadRepository.save(lead);
        auditLogService.log(user, "LEAD_CREATE", "Lead Management", saved.getName());
        return saved;
    }

    public Lead updateLead(String userId, String leadId, Map<String, Object> body) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SALES_AGENT", "ADMIN", "SUPERVISOR");
        Lead lead = leadRepository.findById(leadId).orElseThrow(() -> new RuntimeException("Lead not found"));
        if ("SALES_AGENT".equalsIgnoreCase(user.getRole())
                && lead.getOwnerId() != null
                && !user.getId().equals(lead.getOwnerId())) {
            throw new RuntimeException("Lead not found");
        }
        if (body.get("status") != null) {
            if (!"SALES_AGENT".equalsIgnoreCase(user.getRole())) {
                throw new RuntimeException("Only sales agents can change lead status");
            }
            lead.setStatus((String) body.get("status"));
        }
        if (body.get("name") != null) lead.setName((String) body.get("name"));
        if (body.get("email") != null) lead.setEmail((String) body.get("email"));
        if (body.get("phone") != null) lead.setPhone((String) body.get("phone"));
        if (body.get("company") != null) lead.setCompany((String) body.get("company"));
        if (body.get("score") instanceof Number) lead.setScore(((Number) body.get("score")).intValue());
        lead.setUpdatedAt(LocalDateTime.now());
        return leadRepository.save(lead);
    }

    public Map<String, Object> salesStats(String userId) {
        List<Lead> leads = myLeads(userId);
        long qualified = leads.stream().filter(l -> "qualified".equals(l.getStatus())).count();
        long converted = leads.stream().filter(l -> "converted".equals(l.getStatus())).count();
        return Map.of("totalLeads", leads.size(), "qualifiedLeads", qualified, "convertedLeads", converted);
    }

    private User pickAvailableSalesAgent() {
        List<AgentPresence> availableSales = agentPresenceRepository.findByTeam("sales").stream()
                .filter(p -> "available".equalsIgnoreCase(p.getStatus()))
                .sorted(Comparator.comparing(AgentPresence::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        for (AgentPresence presence : availableSales) {
            User agent = userRepository.findById(presence.getUserId()).orElse(null);
            if (agent != null && agent.isActive() && "SALES_AGENT".equalsIgnoreCase(agent.getRole())) {
                return agent;
            }
        }

        List<User> agents = userRepository.findAll().stream()
                .filter(u -> "SALES_AGENT".equalsIgnoreCase(u.getRole()) && u.isActive())
                .toList();
        if (agents.isEmpty()) {
            throw new RuntimeException("No sales agents are available right now. Please try again later.");
        }

        return agents.stream()
                .min(Comparator.comparingLong(this::openLeadCount))
                .orElse(agents.get(0));
    }

    private long openLeadCount(User agent) {
        return leadRepository.findByOwnerIdOrderByCreatedAtDesc(agent.getId()).stream()
                .filter(l -> !"converted".equalsIgnoreCase(l.getStatus()) && !"lost".equalsIgnoreCase(l.getStatus()))
                .count();
    }

    private String formatQuoteType(String quoteType) {
        return switch (quoteType) {
            case "products_only" -> "Products Only";
            case "products_installation" -> "Products + Installation";
            case "installation_only" -> "Installation Only";
            default -> quoteType;
        };
    }

    private Product requireActiveProduct(String productId) {
        if (productId.isBlank()) {
            throw new RuntimeException("Please select a product");
        }
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Selected product was not found"));
        if (!product.isActive()) {
            throw new RuntimeException("Selected product is no longer available");
        }
        return product;
    }

    private int parseQuantity(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String buildQuoteDetailsSummary(Lead lead) {
        return switch (lead.getQuoteType()) {
            case "products_only" -> String.join(" · ",
                    "Product: " + lead.getProductName(),
                    "Qty: " + lead.getQuantity(),
                    "Delivery: " + lead.getDeliveryAddress());
            case "products_installation" -> String.join(" · ",
                    "Product: " + lead.getProductName(),
                    "Qty: " + lead.getQuantity(),
                    "Install at: " + lead.getInstallationAddress(),
                    "Date: " + lead.getPreferredInstallationDate(),
                    "Site contact: " + lead.getSiteContactName() + " (" + lead.getSiteContactPhone() + ")");
            case "installation_only" -> String.join(" · ",
                    "Type: " + lead.getProductType(),
                    "Install at: " + lead.getInstallationAddress(),
                    "Date: " + lead.getPreferredInstallationDate(),
                    "Existing: " + lead.getExistingProductDetails(),
                    "Site contact: " + lead.getSiteContactName() + " (" + lead.getSiteContactPhone() + ")");
            default -> "";
        };
    }

    private String stringVal(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static final int MAX_QUOTE_QUANTITY = 999;
    private static final int MIN_E164_DIGITS = 10;
    private static final int MAX_E164_DIGITS = 15;

    private String requireInternationalPhone(String phone, String errorMessage) {
        String normalized = phone == null ? "" : phone.trim().replace(" ", "");
        if (!normalized.startsWith("+")) {
            throw new RuntimeException(errorMessage);
        }
        long digitCount = normalized.chars().filter(Character::isDigit).count();
        if (digitCount < MIN_E164_DIGITS || digitCount > MAX_E164_DIGITS) {
            throw new RuntimeException(errorMessage);
        }
        return normalized;
    }

    private void requireQuoteQuantity(int quantity) {
        if (quantity < 1) {
            throw new RuntimeException("Quantity must be at least 1");
        }
        if (quantity > MAX_QUOTE_QUANTITY) {
            throw new RuntimeException("Quantity cannot exceed " + MAX_QUOTE_QUANTITY);
        }
    }
}
