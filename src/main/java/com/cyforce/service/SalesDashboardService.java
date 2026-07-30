package com.cyforce.service;

import com.cyforce.dto.SalesDashboardOverviewResponse;
import com.cyforce.model.Conversation;
import com.cyforce.model.Invoice;
import com.cyforce.model.Lead;
import com.cyforce.model.PaymentTransaction;
import com.cyforce.model.Ticket;
import com.cyforce.model.User;
import com.cyforce.repository.ConversationRepository;
import com.cyforce.repository.InvoiceRepository;
import com.cyforce.repository.LeadRepository;
import com.cyforce.repository.PaymentTransactionRepository;
import com.cyforce.repository.TicketRepository;
import com.cyforce.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SalesDashboardService {

    private static final int MONTHLY_TARGET = 10;
    private static final long VALUE_PER_SCORE_POINT = 10_000L;
    private static final long COMMISSION_RATE_PERCENT = 10L;
    private static final long LEAD_BONUS_KOBO = 250_000L;
    private static final Map<String, String> SOURCE_COLORS = Map.of(
            "website", "#38BDF8",
            "referral", "#34D399",
            "event", "#FBBF24",
            "cold_call", "#A78BFA",
            "linkedin", "#0A66C2",
            "social", "#F472B6"
    );

    private final RequestUserService requestUserService;
    private final LeadRepository leadRepository;
    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final TicketRepository ticketRepository;
    private final PasswordService passwordService;
    private final EmailService emailService;
    private final AuditLogService auditLogService;
    private final AuditReportService auditReportService;

    public SalesDashboardService(RequestUserService requestUserService,
                                 LeadRepository leadRepository,
                                 UserRepository userRepository,
                                 ConversationRepository conversationRepository,
                                 InvoiceRepository invoiceRepository,
                                 PaymentTransactionRepository paymentTransactionRepository,
                                 TicketRepository ticketRepository,
                                 PasswordService passwordService,
                                 EmailService emailService,
                                 AuditLogService auditLogService,
                                 AuditReportService auditReportService) {
        this.requestUserService = requestUserService;
        this.leadRepository = leadRepository;
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.ticketRepository = ticketRepository;
        this.passwordService = passwordService;
        this.emailService = emailService;
        this.auditLogService = auditLogService;
        this.auditReportService = auditReportService;
    }

    public SalesDashboardOverviewResponse overview(String userId) {
        User agent = requestUserService.requireUser(userId);
        requestUserService.requireRole(agent, "SALES_AGENT", "ADMIN", "SUPERVISOR");

        List<Lead> leads = leadRepository.findByOwnerIdOrderByCreatedAtDesc(agent.getId());
        long qualified = leads.stream().filter(l -> "qualified".equals(l.getStatus())).count();
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);

        List<Invoice> paidInvoices = invoiceRepository.findBySalesAgentIdAndStatus(agent.getId(), "paid");
        long converted = paidInvoices.size();
        long convertedThisMonth = paidInvoices.stream()
                .filter(inv -> inv.getPaidAt() != null && !inv.getPaidAt().toLocalDate().isBefore(monthStart))
                .count();
        long commissionKobo = paidInvoices.stream()
                .mapToLong(inv -> (inv.getAmount() * COMMISSION_RATE_PERCENT) / 100L)
                .sum();

        long pipelineValueKobo = leads.stream()
                .filter(l -> !"converted".equals(l.getStatus()) && !"lost".equals(l.getStatus()))
                .mapToLong(l -> (long) l.getScore() * VALUE_PER_SCORE_POINT)
                .sum();

        int conversionRate = leads.isEmpty()
                ? (converted > 0 ? 100 : 0)
                : (int) Math.round((converted * 100.0) / Math.max(leads.size(), converted));
        int monthlyProgress = (int) Math.min(100, Math.round((convertedThisMonth * 100.0) / MONTHLY_TARGET));

        SalesDashboardOverviewResponse.StatsItem stats = new SalesDashboardOverviewResponse.StatsItem(
                leads.size(),
                qualified,
                converted,
                convertedThisMonth,
                MONTHLY_TARGET,
                monthlyProgress,
                pipelineValueKobo,
                conversionRate,
                commissionKobo
        );

        List<SalesDashboardOverviewResponse.PipelineStageItem> pipeline = buildPipeline(leads);
        List<SalesDashboardOverviewResponse.SourceItem> sources = buildSources(leads);
        List<SalesDashboardOverviewResponse.LeadItem> hotLeads = leads.stream()
                .filter(l -> l.getScore() >= 60 && !"converted".equals(l.getStatus()) && !"lost".equals(l.getStatus()))
                .sorted(Comparator.comparingInt(Lead::getScore).reversed())
                .limit(3)
                .map(this::toLeadItem)
                .toList();

        List<SalesDashboardOverviewResponse.LeadItem> opportunities = leads.stream()
                .filter(l -> List.of("qualified", "contacted").contains(l.getStatus()))
                .sorted(Comparator.comparingInt(Lead::getScore).reversed())
                .limit(4)
                .map(this::toLeadItem)
                .toList();

        List<SalesDashboardOverviewResponse.ActivityItem> activity = leads.stream()
                .limit(8)
                .map(l -> new SalesDashboardOverviewResponse.ActivityItem(
                        l.getName() + " — " + (l.getStatus() != null ? l.getStatus() : "new")
                                + " (" + (l.getSource() != null ? l.getSource() : "website") + ")",
                        formatRelative(l.getUpdatedAt() != null ? l.getUpdatedAt() : l.getCreatedAt())
                ))
                .toList();

        List<SalesDashboardOverviewResponse.TaskItem> tasks = buildTasks(leads);
        List<SalesDashboardOverviewResponse.LeaderboardItem> leaderboard = buildLeaderboard(agent.getId());
        SalesDashboardOverviewResponse.LeaderboardItem myRank = leaderboard.stream()
                .filter(SalesDashboardOverviewResponse.LeaderboardItem::isCurrentUser)
                .findFirst()
                .orElse(new SalesDashboardOverviewResponse.LeaderboardItem(0, agent.getId(), agent.getFullName(), 0, 0, true));

        List<Conversation> conversations = conversationRepository.findBySalesAgentIdOrderByUpdatedAtDesc(agent.getId());
        long unread = conversations.stream().filter(c -> "open".equalsIgnoreCase(c.getStatus())).count();
        List<SalesDashboardOverviewResponse.ConversationItem> recentConversations = conversations.stream()
                .limit(5)
                .map(c -> new SalesDashboardOverviewResponse.ConversationItem(
                        c.getId(),
                        c.getCustomerName(),
                        c.getSubject(),
                        formatRelative(c.getUpdatedAt())
                ))
                .toList();

        return new SalesDashboardOverviewResponse(
                stats,
                pipeline,
                sources,
                hotLeads,
                opportunities,
                activity,
                tasks,
                leaderboard,
                myRank,
                unread,
                recentConversations
        );
    }

    public Map<String, Object> bonusBreakdown(String userId) {
        User agent = requestUserService.requireUser(userId);
        requestUserService.requireRole(agent, "SALES_AGENT", "ADMIN", "SUPERVISOR");

        List<Invoice> paidInvoices = invoiceRepository.findBySalesAgentIdAndStatus(agent.getId(), "paid");
        List<Lead> leads = leadRepository.findByOwnerIdOrderByCreatedAtDesc(agent.getId());
        List<Map<String, Object>> items = new ArrayList<>();

        for (Invoice invoice : paidInvoices) {
            long bonusKobo = (invoice.getAmount() * COMMISSION_RATE_PERCENT) / 100L;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", "deal");
            row.put("title", invoice.getDescription() != null ? invoice.getDescription() : "Closed deal");
            row.put("customerName", invoice.getCustomerName());
            row.put("amountKobo", invoice.getAmount());
            row.put("bonusKobo", bonusKobo);
            row.put("bonusLabel", "10% commission");
            row.put("closedAt", invoice.getPaidAt());
            items.add(row);
        }

        for (Lead lead : leads) {
            if (!"converted".equalsIgnoreCase(lead.getStatus())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", "lead");
            row.put("title", lead.getName());
            row.put("customerName", lead.getCompany() != null && !lead.getCompany().isBlank() ? lead.getCompany() : lead.getName());
            row.put("amountKobo", (long) lead.getScore() * VALUE_PER_SCORE_POINT);
            row.put("bonusKobo", LEAD_BONUS_KOBO);
            row.put("bonusLabel", "Lead conversion bonus");
            row.put("closedAt", lead.getUpdatedAt() != null ? lead.getUpdatedAt() : lead.getCreatedAt());
            items.add(row);
        }

        items.sort((a, b) -> {
            LocalDateTime left = (LocalDateTime) a.get("closedAt");
            LocalDateTime right = (LocalDateTime) b.get("closedAt");
            if (left == null && right == null) return 0;
            if (left == null) return 1;
            if (right == null) return -1;
            return right.compareTo(left);
        });

        long totalBonusKobo = items.stream()
                .mapToLong(row -> ((Number) row.get("bonusKobo")).longValue())
                .sum();

        return Map.of(
                "items", items,
                "totalBonusKobo", totalBonusKobo,
                "dealCount", paidInvoices.size(),
                "leadCount", leads.stream().filter(l -> "converted".equalsIgnoreCase(l.getStatus())).count()
        );
    }

    public List<Map<String, Object>> listCustomers(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SALES_AGENT", "ADMIN", "SUPERVISOR", "SUPPORT_AGENT");

        Map<String, List<Ticket>> ticketsByCustomer = ticketRepository.findAll().stream()
                .filter(t -> t.getCustomerId() != null && !t.getCustomerId().isBlank())
                .collect(Collectors.groupingBy(Ticket::getCustomerId));

        List<Invoice> allInvoices = invoiceRepository.findAll();

        Map<String, List<Invoice>> invoicesByCustomer = allInvoices.stream()
                .filter(inv -> inv.getCustomerId() != null && !inv.getCustomerId().isBlank())
                .collect(Collectors.groupingBy(Invoice::getCustomerId));

        Map<String, List<Invoice>> websiteSalesByEmail = allInvoices.stream()
                .filter(this::isWebsiteStoreSale)
                .filter(inv -> inv.getCustomerEmail() != null && !inv.getCustomerEmail().isBlank())
                .collect(Collectors.groupingBy(inv -> inv.getCustomerEmail().toLowerCase(Locale.ROOT)));

        Map<String, List<Conversation>> conversationsByCustomer = conversationRepository.findAll().stream()
                .filter(c -> c.getCustomerId() != null && !c.getCustomerId().isBlank())
                .collect(Collectors.groupingBy(Conversation::getCustomerId));

        List<Map<String, Object>> registered = userRepository.findAll().stream()
                .filter(u -> "CUSTOMER".equalsIgnoreCase(u.getRole()))
                .filter(u -> !UserService.isErasedAccount(u))
                .sorted(Comparator.comparing(User::getFullName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(customer -> {
                    List<Invoice> owned = new ArrayList<>(
                            invoicesByCustomer.getOrDefault(customer.getId(), List.of()));
                    String email = customer.getEmail() != null
                            ? customer.getEmail().toLowerCase(Locale.ROOT)
                            : "";
                    if (!email.isBlank()) {
                        for (Invoice webSale : websiteSalesByEmail.getOrDefault(email, List.of())) {
                            if (owned.stream().noneMatch(inv -> Objects.equals(inv.getId(), webSale.getId()))) {
                                owned.add(webSale);
                            }
                        }
                    }
                    return toCustomerRow(
                            customer,
                            ticketsByCustomer.getOrDefault(customer.getId(), List.of()),
                            owned,
                            conversationsByCustomer.getOrDefault(customer.getId(), List.of())
                    );
                })
                .collect(Collectors.toList());

        Set<String> registeredEmails = registered.stream()
                .map(row -> stringValue(row.get("email")).toLowerCase(Locale.ROOT))
                .filter(email -> !email.isBlank())
                .collect(Collectors.toSet());

        List<Map<String, Object>> guests = websiteSalesByEmail.entrySet().stream()
                .filter(entry -> !registeredEmails.contains(entry.getKey()))
                .map(entry -> toGuestCustomerRow(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(row -> stringValue(row.get("name")), String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<Map<String, Object>> combined = new ArrayList<>(registered);
        combined.addAll(guests);
        return combined;
    }

    /**
     * Every website/store checkout (guest or anonymous buyer details), newest first.
     */
    public List<Map<String, Object>> listStoreOrders(String staffId) {
        User staff = requestUserService.requireUser(staffId);
        requestUserService.requireRole(staff, "SALES_AGENT", "ADMIN", "SUPERVISOR", "SUPPORT_AGENT");

        return invoiceRepository.findAll().stream()
                .filter(this::isWebsiteStoreSale)
                .sorted(Comparator.comparing(Invoice::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toStoreOrderRow)
                .collect(Collectors.toList());
    }

    public byte[] customersReport(String userId, String format) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SALES_AGENT", "ADMIN", "SUPERVISOR", "SUPPORT_AGENT");

        List<Map<String, Object>> customers = listCustomers(userId);
        String[] headers = {
                "Name", "Company", "Email", "Phone", "Type", "Status",
                "Lifetime Value", "Tickets", "Last Contact", "Member Since"
        };
        List<String[]> rows = customers.stream()
                .map(customer -> new String[] {
                        stringValue(customer.get("name")),
                        stringValue(customer.get("company")),
                        stringValue(customer.get("email")),
                        stringValue(customer.get("phone")),
                        stringValue(customer.get("type")),
                        stringValue(customer.get("status")),
                        stringValue(customer.get("lifetimeValue")),
                        stringValue(customer.get("tickets")),
                        stringValue(customer.get("lastContact")),
                        stringValue(customer.get("memberSince")),
                })
                .toList();

        String normalized = normalizeReportFormat(format);
        auditLogService.log(user, "REPORT_GENERATED", "Customer Management",
                "Customer export " + normalized.toUpperCase() + " (" + rows.size() + " records)");

        if ("pdf".equals(normalized)) {
            return auditReportService.toTablePdf("Customer Report", headers, rows);
        }
        return auditReportService.toTableCsv("Customer Report", headers, rows);
    }

    /**
     * Full purchase history for a customer (invoices + payment transactions).
     * Visible to sales, support, supervisors, and admins.
     */
    public Map<String, Object> customerPurchaseHistory(String staffId, String customerId) {
        User staff = requestUserService.requireUser(staffId);
        requestUserService.requireRole(staff, "SALES_AGENT", "ADMIN", "SUPERVISOR", "SUPPORT_AGENT");

        if (isGuestCustomerId(customerId)) {
            return guestPurchaseHistory(guestEmailFromId(customerId));
        }

        User customer = userRepository.findById(customerId)
                .orElseThrow(() -> new RuntimeException("Customer not found"));
        if (!"CUSTOMER".equalsIgnoreCase(customer.getRole())) {
            throw new RuntimeException("Purchase history is only available for customer accounts");
        }
        if (UserService.isErasedAccount(customer)) {
            throw new RuntimeException("Customer not found");
        }

        List<Invoice> invoices = new ArrayList<>(
                invoiceRepository.findByCustomerIdOrderByCreatedAtDesc(customerId));
        if (customer.getEmail() != null && !customer.getEmail().isBlank()) {
            for (Invoice webSale : invoiceRepository
                    .findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc(customer.getEmail())) {
                if (isWebsiteStoreSale(webSale)
                        && invoices.stream().noneMatch(inv -> Objects.equals(inv.getId(), webSale.getId()))) {
                    invoices.add(webSale);
                }
            }
            invoices.sort(Comparator.comparing(Invoice::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        }
        List<PaymentTransaction> transactions =
                paymentTransactionRepository.findByUserIdOrderByCreatedAtDesc(customerId);

        return buildPurchaseHistory(
                customer.getId(),
                customer.getFullName(),
                customer.getEmail(),
                invoices,
                transactions
        );
    }

    public byte[] customerPurchaseHistoryReport(String staffId, String customerId, String format) {
        User staff = requestUserService.requireUser(staffId);
        requestUserService.requireRole(staff, "SALES_AGENT", "ADMIN", "SUPERVISOR", "SUPPORT_AGENT");

        String customerLabel;
        List<Invoice> invoices;
        List<PaymentTransaction> transactions;

        if (isGuestCustomerId(customerId)) {
            String email = guestEmailFromId(customerId);
            invoices = invoiceRepository.findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc(email).stream()
                    .filter(this::isWebsiteStoreSale)
                    .toList();
            if (invoices.isEmpty()) {
                throw new RuntimeException("Guest buyer not found");
            }
            customerLabel = invoices.get(0).getCustomerName() != null
                    ? invoices.get(0).getCustomerName()
                    : email;
            transactions = List.of();
        } else {
            User customer = userRepository.findById(customerId)
                    .orElseThrow(() -> new RuntimeException("Customer not found"));
            if (!"CUSTOMER".equalsIgnoreCase(customer.getRole()) || UserService.isErasedAccount(customer)) {
                throw new RuntimeException("Customer not found");
            }
            customerLabel = customer.getFullName() != null && !customer.getFullName().isBlank()
                    ? customer.getFullName()
                    : customer.getEmail();
            invoices = invoiceRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
            transactions = paymentTransactionRepository.findByUserIdOrderByCreatedAtDesc(customerId);
        }

        long lifetimeKobo = invoices.stream()
                .filter(i -> "paid".equalsIgnoreCase(i.getStatus()))
                .mapToLong(Invoice::getAmount)
                .sum();

        String[] headers = {
                "Type", "Description", "Amount", "Status", "Reference", "Provider",
                "Due Date", "Paid At", "Created At"
        };
        List<String[]> rows = new ArrayList<>();
        for (Invoice invoice : invoices) {
            rows.add(new String[] {
                    "Invoice",
                    stringValue(invoice.getDescription()),
                    formatReportAmount(invoice.getAmount()),
                    stringValue(invoice.getStatus()),
                    stringValue(invoice.getPaymentTransactionId() != null
                            ? invoice.getPaymentTransactionId()
                            : invoice.getId()),
                    "",
                    formatReportDateTime(invoice.getDueDate()),
                    formatReportDateTime(invoice.getPaidAt()),
                    formatReportDateTime(invoice.getCreatedAt()),
            });
        }
        for (PaymentTransaction tx : transactions) {
            rows.add(new String[] {
                    "Transaction",
                    stringValue(tx.getDescription()),
                    formatReportAmount(tx.getAmount()),
                    stringValue(tx.getStatus()),
                    stringValue(tx.getReference()),
                    stringValue(tx.getProvider()),
                    "",
                    formatReportDateTime(tx.getVerifiedAt()),
                    formatReportDateTime(tx.getCreatedAt()),
            });
        }

        String title = "Purchase History - " + customerLabel
                + " | Lifetime Value " + formatLifetimeValue(lifetimeKobo);

        String normalized = normalizeReportFormat(format);
        auditLogService.log(staff, "REPORT_GENERATED", "Customer Management",
                "Purchase history export " + normalized.toUpperCase()
                        + " for " + customerLabel + " (" + rows.size() + " records)");

        if ("pdf".equals(normalized)) {
            return auditReportService.toTablePdf(title, headers, rows);
        }
        return auditReportService.toTableCsv(title, headers, rows);
    }

    public Map<String, Object> createCustomer(String userId, Map<String, String> body) {
        User staff = requestUserService.requireUser(userId);
        requestUserService.requireRole(staff, "SALES_AGENT", "ADMIN", "SUPERVISOR", "SUPPORT_AGENT");

        String email = body.get("email");
        if (email == null || email.isBlank()) {
            throw new RuntimeException("Email is required");
        }
        email = email.trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new RuntimeException("Email already registered");
        }

        String fullName = body.get("fullName");
        if (fullName == null || fullName.isBlank()) {
            throw new RuntimeException("Full name is required");
        }

        String tempPassword = body.get("password");
        if (tempPassword == null || tempPassword.isBlank()) {
            tempPassword = passwordService.generateTemporaryPassword();
        }

        User customer = new User();
        customer.setFullName(fullName.trim());
        customer.setEmail(email);
        customer.setPhone(body.getOrDefault("phone", "").trim());
        customer.setCompanyName(body.get("companyName") != null ? body.get("companyName").trim() : null);
        customer.setCustomerType(normalizeCustomerType(body.get("customerType")));
        customer.setRole("CUSTOMER");
        customer.setAuthProvider("LOCAL");
        customer.setPassword(passwordService.encode(tempPassword));
        customer.setMustChangePassword(true);
        customer.setEmailVerified(true);
        customer.setEmailVerifiedAt(LocalDateTime.now());
        customer.setActive(true);
        customer.setCreatedAt(LocalDateTime.now());
        customer.setUpdatedAt(LocalDateTime.now());

        User saved = userRepository.save(customer);
        auditLogService.log(staff, "CUSTOMER_CREATE", "Customer Management", saved.getEmail());

        try {
            emailService.sendWelcomeCredentialsEmail(saved.getEmail(), saved.getFullName(), tempPassword);
        } catch (Exception e) {
            System.err.println("Failed to send welcome credentials email: " + e.getMessage());
        }

        return toCustomerRow(saved, List.of(), List.of(), List.of());
    }

    private Map<String, Object> toCustomerRow(User customer,
                                              List<Ticket> customerTickets,
                                              List<Invoice> customerInvoices,
                                              List<Conversation> customerConversations) {
        long ticketCount = customerTickets.size();
        long lifetimeKobo = customerInvoices.stream()
                .filter(inv -> "paid".equalsIgnoreCase(inv.getStatus()))
                .mapToLong(Invoice::getAmount)
                .sum();
        LocalDateTime lastContact = resolveLastContact(customer, customerTickets, customerConversations);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", customer.getId());
        row.put("name", customer.getFullName());
        row.put("email", customer.getEmail());
        row.put("phone", customer.getPhone());
        row.put("company", customer.getCompanyName() != null && !customer.getCompanyName().isBlank()
                ? customer.getCompanyName() : "—");
        row.put("type", formatCustomerType(customer.getCustomerType()));
        row.put("status", customer.isActive() ? "active" : "inactive");
        row.put("tickets", ticketCount);
        row.put("lifetimeValueKobo", lifetimeKobo);
        row.put("lifetimeValue", formatLifetimeValue(lifetimeKobo));
        row.put("lastContact", formatLastContact(lastContact));
        row.put("lastContactAt", lastContact);
        row.put("avatarUrl", customer.getAvatarUrl());
        row.put("memberSince", customer.getCreatedAt() != null
                ? customer.getCreatedAt().format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                : null);
        row.put("guest", false);
        return row;
    }

    private Map<String, Object> toGuestCustomerRow(String email, List<Invoice> invoices) {
        Invoice latest = invoices.stream()
                .max(Comparator.comparing(Invoice::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        long lifetimeKobo = invoices.stream()
                .filter(inv -> "paid".equalsIgnoreCase(inv.getStatus()))
                .mapToLong(Invoice::getAmount)
                .sum();
        LocalDateTime lastContact = latest != null
                ? (latest.getPaidAt() != null ? latest.getPaidAt() : latest.getCreatedAt())
                : null;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", guestCustomerId(email));
        row.put("name", latest != null && latest.getCustomerName() != null
                ? latest.getCustomerName()
                : email);
        row.put("email", email);
        row.put("phone", latest != null ? latest.getCustomerPhone() : null);
        row.put("company", latest != null && latest.getCompanyName() != null && !latest.getCompanyName().isBlank()
                ? latest.getCompanyName()
                : "—");
        row.put("type", "Guest");
        row.put("status", lifetimeKobo > 0 ? "active" : "inactive");
        row.put("tickets", 0);
        row.put("lifetimeValueKobo", lifetimeKobo);
        row.put("lifetimeValue", formatLifetimeValue(lifetimeKobo));
        row.put("lastContact", formatLastContact(lastContact));
        row.put("lastContactAt", lastContact);
        row.put("avatarUrl", null);
        row.put("memberSince", latest != null && latest.getCreatedAt() != null
                ? latest.getCreatedAt().format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                : null);
        row.put("guest", true);
        row.put("deliveryAddress", latest != null ? latest.getDeliveryAddress() : null);
        return row;
    }

    private Map<String, Object> guestPurchaseHistory(String email) {
        List<Invoice> invoices = invoiceRepository.findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc(email).stream()
                .filter(this::isWebsiteStoreSale)
                .toList();
        if (invoices.isEmpty()) {
            throw new RuntimeException("Guest buyer not found");
        }
        Invoice sample = invoices.get(0);
        return buildPurchaseHistory(
                guestCustomerId(email),
                sample.getCustomerName(),
                email,
                invoices,
                List.of()
        );
    }

    private Map<String, Object> buildPurchaseHistory(String customerId,
                                                     String customerName,
                                                     String customerEmail,
                                                     List<Invoice> invoices,
                                                     List<PaymentTransaction> transactions) {
        long paidCount = invoices.stream().filter(i -> "paid".equalsIgnoreCase(i.getStatus())).count();
        long pendingCount = invoices.stream()
                .filter(i -> "pending".equalsIgnoreCase(i.getStatus()) || "unpaid".equalsIgnoreCase(i.getStatus()))
                .count();
        long lifetimeKobo = invoices.stream()
                .filter(i -> "paid".equalsIgnoreCase(i.getStatus()))
                .mapToLong(Invoice::getAmount)
                .sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customerId", customerId);
        result.put("customerName", customerName);
        result.put("customerEmail", customerEmail);
        result.put("lifetimeValue", formatLifetimeValue(lifetimeKobo));
        result.put("lifetimeValueKobo", lifetimeKobo);
        result.put("paidCount", paidCount);
        result.put("pendingCount", pendingCount);
        result.put("invoiceCount", invoices.size());
        result.put("invoices", invoices);
        result.put("transactions", transactions);
        result.put("guest", isGuestCustomerId(customerId));
        return result;
    }

    /**
     * Website/store sale: guest checkout flag, or anonymous invoice with buyer email
     * and no sales-chat attribution.
     */
    private boolean isWebsiteStoreSale(Invoice invoice) {
        if (invoice == null) {
            return false;
        }
        if (invoice.isGuestPurchase()) {
            return true;
        }
        boolean noAccount = invoice.getCustomerId() == null || invoice.getCustomerId().isBlank();
        boolean hasBuyerEmail = invoice.getCustomerEmail() != null && !invoice.getCustomerEmail().isBlank();
        boolean notSalesDeal = (invoice.getSalesAgentId() == null || invoice.getSalesAgentId().isBlank())
                && (invoice.getConversationId() == null || invoice.getConversationId().isBlank());
        return noAccount && hasBuyerEmail && notSalesDeal;
    }

    private Map<String, Object> toStoreOrderRow(Invoice invoice) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", invoice.getId());
        row.put("customerName", invoice.getCustomerName());
        row.put("customerEmail", invoice.getCustomerEmail());
        row.put("customerPhone", invoice.getCustomerPhone());
        row.put("companyName", invoice.getCompanyName());
        row.put("deliveryAddress", invoice.getDeliveryAddress());
        row.put("description", invoice.getDescription());
        row.put("amount", invoice.getAmount());
        row.put("amountLabel", formatLifetimeValue(invoice.getAmount()));
        row.put("status", invoice.getStatus());
        row.put("guestPurchase", invoice.isGuestPurchase() || isWebsiteStoreSale(invoice));
        row.put("createdAt", invoice.getCreatedAt());
        row.put("paidAt", invoice.getPaidAt());
        row.put("buyerId", invoice.getCustomerEmail() != null && !invoice.getCustomerEmail().isBlank()
                ? guestCustomerId(invoice.getCustomerEmail())
                : null);
        return row;
    }

    private static boolean isGuestCustomerId(String customerId) {
        return customerId != null && customerId.startsWith("guest:");
    }

    private static String guestCustomerId(String email) {
        return "guest:" + email.trim().toLowerCase(Locale.ROOT);
    }

    private static String guestEmailFromId(String customerId) {
        if (!isGuestCustomerId(customerId)) {
            throw new RuntimeException("Invalid guest buyer id");
        }
        String email = customerId.substring("guest:".length()).trim().toLowerCase(Locale.ROOT);
        if (email.isBlank()) {
            throw new RuntimeException("Invalid guest buyer id");
        }
        return email;
    }

    private LocalDateTime resolveLastContact(User customer,
                                             List<Ticket> tickets,
                                             List<Conversation> conversations) {
        LocalDateTime latest = customer.getLastActivityAt();
        if (customer.getLastLoginAt() != null
                && (latest == null || customer.getLastLoginAt().isAfter(latest))) {
            latest = customer.getLastLoginAt();
        }
        for (Ticket ticket : tickets) {
            LocalDateTime candidate = ticket.getUpdatedAt() != null ? ticket.getUpdatedAt() : ticket.getCreatedAt();
            if (candidate != null && (latest == null || candidate.isAfter(latest))) {
                latest = candidate;
            }
        }
        for (Conversation conversation : conversations) {
            LocalDateTime candidate = conversation.getUpdatedAt() != null
                    ? conversation.getUpdatedAt()
                    : conversation.getCreatedAt();
            if (candidate != null && (latest == null || candidate.isAfter(latest))) {
                latest = candidate;
            }
        }
        return latest;
    }

    private String formatLifetimeValue(long kobo) {
        if (kobo <= 0) {
            return "₦0";
        }
        double naira = kobo / 100.0;
        if (naira >= 1_000_000) {
            return String.format("₦%.1fM", naira / 1_000_000);
        }
        if (naira >= 1_000) {
            return String.format("₦%.0fK", naira / 1_000);
        }
        return String.format("₦%,.0f", naira);
    }

    private String formatLastContact(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "—";
        }
        long minutes = ChronoUnit.MINUTES.between(dateTime, LocalDateTime.now());
        if (minutes < 1) {
            return "Just now";
        }
        if (minutes < 60) {
            return minutes + (minutes == 1 ? " min ago" : " mins ago");
        }
        long hours = ChronoUnit.HOURS.between(dateTime, LocalDateTime.now());
        if (hours < 24) {
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        }
        long days = ChronoUnit.DAYS.between(dateTime.toLocalDate(), LocalDate.now());
        if (days == 1) {
            return "1 day ago";
        }
        if (days < 7) {
            return days + " days ago";
        }
        if (days < 14) {
            return "1 week ago";
        }
        long weeks = days / 7;
        if (weeks < 5) {
            return weeks + " weeks ago";
        }
        return dateTime.format(DateTimeFormatter.ofPattern("MMM d, yyyy"));
    }

    private String normalizeCustomerType(String type) {
        if (type == null || type.isBlank()) {
            return "individual";
        }
        return type.trim().toLowerCase();
    }

    private List<SalesDashboardOverviewResponse.PipelineStageItem> buildPipeline(List<Lead> leads) {
        return List.of(
                stage("new", "New", leads.stream().filter(l -> "new".equals(l.getStatus())).count()),
                stage("contacted", "Contacted", leads.stream().filter(l -> "contacted".equals(l.getStatus())).count()),
                stage("qualified", "Qualified", leads.stream().filter(l -> "qualified".equals(l.getStatus())).count()),
                stage("proposal", "Proposal", leads.stream()
                        .filter(l -> "qualified".equals(l.getStatus()) && l.getScore() >= 70 && l.getScore() < 85).count()),
                stage("negotiation", "Negotiation", leads.stream()
                        .filter(l -> "qualified".equals(l.getStatus()) && l.getScore() >= 85).count()),
                stage("converted", "Closed Won", leads.stream().filter(l -> "converted".equals(l.getStatus())).count())
        );
    }

    private SalesDashboardOverviewResponse.PipelineStageItem stage(String key, String label, long count) {
        return new SalesDashboardOverviewResponse.PipelineStageItem(key, label, count);
    }

    private List<SalesDashboardOverviewResponse.SourceItem> buildSources(List<Lead> leads) {
        Map<String, Long> counts = leads.stream()
                .collect(Collectors.groupingBy(
                        l -> l.getSource() == null ? "website" : l.getSource().toLowerCase(),
                        Collectors.counting()
                ));
        return counts.entrySet().stream()
                .map(e -> new SalesDashboardOverviewResponse.SourceItem(
                        e.getKey().replace('_', ' '),
                        e.getValue(),
                        SOURCE_COLORS.getOrDefault(e.getKey(), "#38BDF8")
                ))
                .sorted(Comparator.comparingLong(SalesDashboardOverviewResponse.SourceItem::getValue).reversed())
                .toList();
    }

    private List<SalesDashboardOverviewResponse.TaskItem> buildTasks(List<Lead> leads) {
        LocalDateTime now = LocalDateTime.now();
        return leads.stream()
                .filter(l -> !"converted".equals(l.getStatus()) && !"lost".equals(l.getStatus()))
                .filter(l -> {
                    LocalDateTime updated = l.getUpdatedAt() != null ? l.getUpdatedAt() : l.getCreatedAt();
                    if (updated == null) return true;
                    long days = ChronoUnit.DAYS.between(updated.toLocalDate(), now.toLocalDate());
                    return days >= 1 || "new".equals(l.getStatus());
                })
                .sorted(Comparator.comparingInt(Lead::getScore).reversed())
                .limit(5)
                .map(l -> {
                    String type = "new".equals(l.getStatus()) ? "Call" : "Follow-up";
                    if ("qualified".equals(l.getStatus()) && l.getScore() >= 80) type = "Demo";
                    return new SalesDashboardOverviewResponse.TaskItem(
                            l.getId(),
                            type.equals("Demo") ? "Schedule" : "Today",
                            type,
                            l.getCompany() != null && !l.getCompany().isBlank() ? l.getCompany() : l.getName(),
                            l.getName(),
                            l.getId(),
                            false
                    );
                })
                .toList();
    }

    private List<SalesDashboardOverviewResponse.LeaderboardItem> buildLeaderboard(String currentUserId) {
        List<User> agents = userRepository.findAll().stream()
                .filter(u -> "SALES_AGENT".equalsIgnoreCase(u.getRole()))
                .toList();

        List<SalesDashboardOverviewResponse.LeaderboardItem> ranked = new ArrayList<>();
        for (User agent : agents) {
            long converted = invoiceRepository.findBySalesAgentIdAndStatus(agent.getId(), "paid").size();
            List<Lead> agentLeads = leadRepository.findByOwnerIdOrderByCreatedAtDesc(agent.getId());
            long qualified = agentLeads.stream().filter(l -> "qualified".equals(l.getStatus())).count();
            ranked.add(new SalesDashboardOverviewResponse.LeaderboardItem(
                    0, agent.getId(), agent.getFullName(), converted, qualified, agent.getId().equals(currentUserId)
            ));
        }

        ranked.sort(Comparator.comparingLong(SalesDashboardOverviewResponse.LeaderboardItem::getConverted).reversed()
                .thenComparingLong(SalesDashboardOverviewResponse.LeaderboardItem::getQualified).reversed());

        for (int i = 0; i < ranked.size(); i++) {
            ranked.get(i).setRank(i + 1);
        }
        return ranked;
    }

    public Map<String, Object> dealsComparison(String userId) {
        User agent = requestUserService.requireUser(userId);
        requestUserService.requireRole(agent, "SALES_AGENT", "ADMIN", "SUPERVISOR");

        List<User> agents = userRepository.findAll().stream()
                .filter(u -> "SALES_AGENT".equalsIgnoreCase(u.getRole()) && u.isActive())
                .sorted(Comparator.comparing(User::getFullName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();

        List<Map<String, Object>> agentRows = new ArrayList<>();
        for (User salesAgent : agents) {
            long dealsClosed = invoiceRepository.findBySalesAgentIdAndStatus(salesAgent.getId(), "paid").size();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", salesAgent.getId());
            row.put("agentName", salesAgent.getFullName());
            row.put("dealsClosed", dealsClosed);
            row.put("averageRating", salesAgent.getAverageRating());
            row.put("ratingCount", salesAgent.getRatingCount());
            agentRows.add(row);
        }

        agentRows.sort((a, b) -> Long.compare(
                ((Number) b.get("dealsClosed")).longValue(),
                ((Number) a.get("dealsClosed")).longValue()
        ));

        for (int i = 0; i < agentRows.size(); i++) {
            agentRows.get(i).put("rank", i + 1);
        }

        long myDeals = invoiceRepository.findBySalesAgentIdAndStatus(agent.getId(), "paid").size();
        int myRank = 0;
        for (int i = 0; i < agentRows.size(); i++) {
            if (agent.getId().equals(agentRows.get(i).get("agentId"))) {
                myRank = i + 1;
                break;
            }
        }

        double teamAverage = agentRows.isEmpty() ? 0
                : agentRows.stream().mapToLong(r -> ((Number) r.get("dealsClosed")).longValue()).average().orElse(0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agents", agentRows);
        result.put("myDealsClosed", myDeals);
        result.put("myRank", myRank);
        result.put("teamAverage", Math.round(teamAverage * 10.0) / 10.0);

        if (!agentRows.isEmpty()) {
            Map<String, Object> highest = agentRows.get(0);
            Map<String, Object> lowest = agentRows.get(agentRows.size() - 1);
            result.put("highest", Map.of(
                    "agentName", highest.get("agentName"),
                    "dealsClosed", highest.get("dealsClosed")
            ));
            result.put("lowest", Map.of(
                    "agentName", lowest.get("agentName"),
                    "dealsClosed", lowest.get("dealsClosed")
            ));
        }

        return result;
    }

    private SalesDashboardOverviewResponse.LeadItem toLeadItem(Lead l) {
        return new SalesDashboardOverviewResponse.LeadItem(
                l.getId(),
                l.getName(),
                l.getEmail(),
                l.getPhone(),
                l.getCompany(),
                l.getSource(),
                l.getStatus(),
                l.getScore(),
                (long) l.getScore() * VALUE_PER_SCORE_POINT,
                formatRelative(l.getUpdatedAt() != null ? l.getUpdatedAt() : l.getCreatedAt())
        );
    }

    private String formatRelative(LocalDateTime dateTime) {
        if (dateTime == null) return "Recently";
        long days = ChronoUnit.DAYS.between(dateTime.toLocalDate(), LocalDate.now());
        if (days == 0) return "Today";
        if (days == 1) return "Yesterday";
        return days + " days ago";
    }

    private String formatCustomerType(String type) {
        if (type == null || type.isBlank()) return "Individual";
        String normalized = type.toLowerCase();
        if (normalized.equals("enterprise")) return "Enterprise";
        if (normalized.equals("business")) return "Business";
        return "Individual";
    }

    private String normalizeReportFormat(String format) {
        if (format == null || format.isBlank()) {
            return "csv";
        }
        String value = format.trim().toLowerCase(Locale.ROOT);
        if (!"csv".equals(value) && !"pdf".equals(value)) {
            throw new RuntimeException("Unsupported report format. Use csv or pdf.");
        }
        return value;
    }

    private String formatReportAmount(long kobo) {
        return String.format(Locale.US, "₦%,.2f", kobo / 100.0);
    }

    private String formatReportDateTime(LocalDateTime value) {
        return value == null ? "" : value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
