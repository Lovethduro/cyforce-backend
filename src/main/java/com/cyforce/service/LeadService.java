package com.cyforce.service;

import com.cyforce.model.Conversation;
import com.cyforce.model.Lead;
import com.cyforce.model.Product;
import com.cyforce.model.User;
import com.cyforce.repository.LeadRepository;
import com.cyforce.repository.ProductRepository;
import com.cyforce.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private final AuditReportService auditReportService;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ProductRepository productRepository;
    private final MessagingService messagingService;
    private final EmailService emailService;
    private final SalesAgentLoadService salesAgentLoadService;

    public LeadService(LeadRepository leadRepository,
                       RequestUserService requestUserService,
                       AuditLogService auditLogService,
                       AuditReportService auditReportService,
                       UserRepository userRepository,
                       NotificationService notificationService,
                       ProductRepository productRepository,
                       MessagingService messagingService,
                       EmailService emailService,
                       SalesAgentLoadService salesAgentLoadService) {
        this.leadRepository = leadRepository;
        this.requestUserService = requestUserService;
        this.auditLogService = auditLogService;
        this.auditReportService = auditReportService;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.productRepository = productRepository;
        this.messagingService = messagingService;
        this.emailService = emailService;
        this.salesAgentLoadService = salesAgentLoadService;
    }

    public List<Lead> myLeads(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SALES_AGENT", "ADMIN", "SUPERVISOR");
        return leadRepository.findByOwnerIdOrderByCreatedAtDesc(user.getId());
    }

    public List<Lead> allLeads(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "ADMIN", "SUPERVISOR");
        return leadRepository.findTop200ByOrderByCreatedAtDesc();
    }

    public byte[] leadsReport(String userId, String format) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SALES_AGENT", "ADMIN", "SUPERVISOR");

        List<Lead> leads = "SALES_AGENT".equalsIgnoreCase(user.getRole())
                ? leadRepository.findByOwnerIdOrderByCreatedAtDesc(user.getId())
                : leadRepository.findTop200ByOrderByCreatedAtDesc();

        String[] headers = {
                "Name", "Email", "Phone", "Company", "Quote Type", "Details",
                "Source", "Owner", "Status", "Stage", "Amount"
        };
        List<String[]> rows = leads.stream()
                .filter(lead -> lead != null && !isAnonymizedLead(lead) && !isDemoLead(lead))
                .map(lead -> {
                    int score = lead.getScore() > 0 ? lead.getScore() : 50;
                    long amount = (long) score * VALUE_PER_SCORE_POINT;
                    return new String[] {
                            nullToEmpty(lead.getName()),
                            nullToEmpty(lead.getEmail()),
                            nullToEmpty(lead.getPhone()),
                            nullToEmpty(lead.getCompany()),
                            formatQuoteTypeLabel(lead.getQuoteType()),
                            nullToEmpty(lead.getDetails()),
                            nullToEmpty(lead.getSource()),
                            lead.getOwnerName() == null || lead.getOwnerName().isBlank() ? "Unassigned" : lead.getOwnerName(),
                            nullToEmpty(lead.getStatus()).isBlank() ? "new" : lead.getStatus(),
                            stageFromLead(lead).replace('_', ' '),
                            "NGN " + String.format(Locale.US, "%,d", amount),
                    };
                })
                .toList();

        String normalized = normalizeReportFormat(format);
        auditLogService.log(user, "REPORT_GENERATED", "Lead Management",
                "Lead export " + normalized.toUpperCase() + " (" + rows.size() + " records)");

        if ("pdf".equals(normalized)) {
            return auditReportService.toTablePdf("Leads Report", headers, rows);
        }
        return auditReportService.toTableCsv("Leads Report", headers, rows);
    }

    private static final long VALUE_PER_SCORE_POINT = 10_000L;

    /**
     * Pipeline deals sourced only from MongoDB leads (no client-side mock rows).
     * Amount uses the same score-based pipeline value as the sales dashboard.
     */
    public List<Map<String, Object>> pipelineDeals(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SALES_AGENT", "ADMIN", "SUPERVISOR");

        List<Lead> leads = "SALES_AGENT".equalsIgnoreCase(user.getRole())
                ? leadRepository.findByOwnerIdOrderByCreatedAtDesc(user.getId())
                : leadRepository.findTop200ByOrderByCreatedAtDesc();

        Map<String, Lead> uniqueById = new LinkedHashMap<>();
        Map<String, Lead> uniqueByFingerprint = new LinkedHashMap<>();
        for (Lead lead : leads) {
            if (lead == null || lead.getId() == null || isAnonymizedLead(lead)) {
                continue;
            }
            uniqueById.putIfAbsent(lead.getId(), lead);
        }

        List<Lead> ordered = new ArrayList<>(uniqueById.values());
        ordered.sort(Comparator.comparing(Lead::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        for (Lead lead : ordered) {
            uniqueByFingerprint.putIfAbsent(dealFingerprint(lead), lead);
        }

        return uniqueByFingerprint.values().stream()
                .sorted(Comparator.comparing(Lead::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toDealRow)
                .toList();
    }

    private Map<String, Object> toDealRow(Lead lead) {
        long amount = resolveDealAmount(lead);
        String stage = stageFromLead(lead);
        String customer = lead.getName() != null ? lead.getName().trim() : "";
        String company = lead.getCompany() != null ? lead.getCompany().trim() : "";
        String dealName = !company.isBlank() ? company + " - " + customer : customer;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", lead.getId());
        row.put("deal", dealName);
        row.put("customer", customer);
        row.put("company", company.isBlank() ? null : company);
        row.put("amount", amount);
        row.put("amountLabel", amount > 0 ? "₦" + String.format(Locale.US, "%,d", amount) : "—");
        row.put("stage", stage);
        row.put("status", lead.getStatus());
        row.put("productId", lead.getProductId());
        row.put("productName", lead.getProductName());
        row.put("ownerId", lead.getOwnerId());
        row.put("ownerName", lead.getOwnerName());
        row.put("createdAt", lead.getCreatedAt());
        return row;
    }

    private long resolveDealAmount(Lead lead) {
        int score = lead.getScore() > 0 ? lead.getScore() : 50;
        return (long) score * VALUE_PER_SCORE_POINT;
    }

    private static String stageFromLead(Lead lead) {
        String status = lead.getStatus() == null ? "" : lead.getStatus().trim().toLowerCase(Locale.ROOT);
        int score = lead.getScore();
        return switch (status) {
            case "converted" -> "closed_won";
            case "lost" -> "closed_lost";
            case "qualified" -> score >= 85 ? "negotiation" : score >= 70 ? "proposal" : "qualified";
            case "contacted" -> "discovery";
            default -> "new";
        };
    }

    private static String dealFingerprint(Lead lead) {
        return String.join("|",
                normalizeKey(lead.getEmail()),
                normalizeKey(lead.getName()),
                normalizeKey(lead.getCompany()),
                normalizeKey(lead.getStatus()),
                String.valueOf(lead.getScore()),
                normalizeKey(lead.getProductId()));
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isAnonymizedLead(Lead lead) {
        String name = lead.getName() == null ? "" : lead.getName().trim();
        if (name.equalsIgnoreCase("Deleted User") || name.toLowerCase(Locale.ROOT).contains("[redacted")) {
            return true;
        }
        String email = lead.getEmail() == null ? "" : lead.getEmail().trim().toLowerCase(Locale.ROOT);
        return email.endsWith("@removed.local") || email.startsWith("deleted-");
    }

    private static boolean isDemoLead(Lead lead) {
        String email = lead.getEmail() == null ? "" : lead.getEmail().trim().toLowerCase(Locale.ROOT);
        String name = lead.getName() == null ? "" : lead.getName().trim().toLowerCase(Locale.ROOT);
        String company = lead.getCompany() == null ? "" : lead.getCompany().trim().toLowerCase(Locale.ROOT);
        if (email.endsWith("@example.com")) {
            return true;
        }
        if ("ibrahim@solar.ng".equals(email) || (name.equals("ibrahim musa") && company.equals("solar ng"))) {
            return true;
        }
        if (name.equals("john smith") && company.equals("acme corp")) {
            return true;
        }
        return name.equals("sarah johnson") && company.equals("techstart");
    }

    private static String formatQuoteTypeLabel(String quoteType) {
        if (quoteType == null || quoteType.isBlank()) {
            return "-";
        }
        return switch (quoteType.trim().toLowerCase(Locale.ROOT)) {
            case "products_only" -> "Products Only";
            case "products_installation" -> "Products + Installation";
            case "installation_only" -> "Installation Only";
            default -> quoteType.replace('_', ' ');
        };
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeReportFormat(String format) {
        String value = format == null ? "csv" : format.trim().toLowerCase(Locale.ROOT);
        if (!"csv".equals(value) && !"pdf".equals(value)) {
            throw new RuntimeException("Unsupported report format. Use csv or pdf.");
        }
        return value;
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

        User agent = salesAgentLoadService.pickAvailableSalesAgentForQuotes();
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

        String name = stringVal(body.get("name"));
        String email = stringVal(body.get("email")).toLowerCase(Locale.ROOT);
        String phone = stringVal(body.get("phone"));
        if (name.isBlank()) {
            throw new RuntimeException("Name is required");
        }
        if (email.isBlank() || !email.contains("@")) {
            throw new RuntimeException("A valid email is required");
        }
        phone = requireInternationalPhone(phone, "Phone number must include country code (e.g. +2348012345678)");

        Lead lead = new Lead();
        lead.setName(name);
        lead.setEmail(email);
        lead.setPhone(phone);
        lead.setCompany(stringVal(body.get("company")).isBlank() ? null : stringVal(body.get("company")));
        lead.setSource(stringVal(body.getOrDefault("source", "website")).isBlank()
                ? "website"
                : stringVal(body.getOrDefault("source", "website")));
        lead.setStatus("new");
        lead.setScore(body.get("score") instanceof Number ? ((Number) body.get("score")).intValue() : 50);
        boolean pendingApproval = Boolean.TRUE.equals(body.get("pendingApproval"))
                || "true".equalsIgnoreCase(stringVal(body.get("pendingApproval")));
        if ("SALES_AGENT".equalsIgnoreCase(user.getRole())) {
            lead.setOwnerId(user.getId());
            lead.setOwnerName(user.getFullName());
        } else if (pendingApproval) {
            // Supervisor-created leads stay unassigned until admin approves the requested agent.
            lead.setOwnerId(null);
            lead.setOwnerName(null);
        } else {
            // Admins (or direct staff creates) assign immediately to a real sales agent.
            User agent = resolveOwnerForStaffCreatedLead(stringVal(body.get("ownerId")));
            lead.setOwnerId(agent.getId());
            lead.setOwnerName(agent.getFullName());
        }
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

    private User resolveOwnerForStaffCreatedLead(String ownerId) {
        if (!ownerId.isBlank()) {
            User agent = userRepository.findById(ownerId)
                    .orElseThrow(() -> new RuntimeException("Selected sales agent was not found"));
            if (!"SALES_AGENT".equalsIgnoreCase(agent.getRole())
                    || !agent.isActive()
                    || UserService.isErasedAccount(agent)) {
                throw new RuntimeException("Select an active sales agent as the lead owner");
            }
            return agent;
        }
        return salesAgentLoadService.pickLightestLoadAgent();
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
