package com.cyforce.service;

import com.cyforce.config.PaymentProperties;
import com.cyforce.model.Invoice;
import com.cyforce.model.PaymentTransaction;
import com.cyforce.model.Product;
import com.cyforce.model.User;
import com.cyforce.repository.ConversationRepository;
import com.cyforce.repository.InvoiceRepository;
import com.cyforce.repository.PaymentTransactionRepository;
import com.cyforce.repository.ProductRepository;
import com.cyforce.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentProperties properties;
    private final PaymentTransactionRepository transactionRepository;
    private final InvoiceRepository invoiceRepository;
    private final ConversationRepository conversationRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final RequestUserService requestUserService;
    private final EmailService emailService;
    private final RestClient restClient;

    public PaymentService(PaymentProperties properties,
                          PaymentTransactionRepository transactionRepository,
                          InvoiceRepository invoiceRepository,
                          ConversationRepository conversationRepository,
                          ProductRepository productRepository,
                          UserRepository userRepository,
                          NotificationService notificationService,
                          RequestUserService requestUserService,
                          EmailService emailService) {
        this.properties = properties;
        this.transactionRepository = transactionRepository;
        this.invoiceRepository = invoiceRepository;
        this.conversationRepository = conversationRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.requestUserService = requestUserService;
        this.emailService = emailService;
        this.restClient = RestClient.create();
    }

    public Map<String, Object> checkoutCart(String userId, Map<String, Object> body) {
        User user = requestUserService.requireUser(userId);
        if (!isCustomer(user) && !isStaff(user)) {
            throw new RuntimeException("Checkout is not available for this account");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        if (items == null || items.isEmpty()) {
            throw new RuntimeException("Cart is empty");
        }

        String provider = stringVal(body.get("provider"), "paystack");
        long totalKobo = 0;
        StringBuilder description = new StringBuilder("Cart purchase: ");

        for (Map<String, Object> item : items) {
            String productId = stringVal(item.get("productId"), null);
            if (productId == null || productId.isBlank()) {
                throw new RuntimeException("Invalid cart item");
            }
            int quantity = parseQuantity(item.get("quantity"));
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new RuntimeException("Product not found: " + productId));
            if (!product.isActive() || !product.isInStock()) {
                throw new RuntimeException("Product unavailable: " + product.getName());
            }
            if (product.getStockQuantity() > 0 && quantity > product.getStockQuantity()) {
                throw new RuntimeException("Only " + product.getStockQuantity() + " units left for " + product.getName());
            }
            long unitPrice = resolveUnitPrice(item, product);
            long lineKobo = unitPrice * 100L * quantity;
            totalKobo += lineKobo;
            description.append(product.getName()).append(" x").append(quantity).append(", ");
        }

        if (totalKobo <= 0) {
            throw new RuntimeException("Invalid cart total");
        }

        long originalTotalKobo = totalKobo;
        StaffDiscount discount = staffDiscountFor(user);
        if (discount.percent() > 0) {
            totalKobo = Math.max(0, totalKobo - discount.amountKobo(originalTotalKobo));
            description.append(" (staff discount ").append(discount.percent()).append("%)");
        }

        for (Map<String, Object> item : items) {
            String productId = stringVal(item.get("productId"), null);
            int quantity = parseQuantity(item.get("quantity"));
            productRepository.findById(productId).ifPresent(product -> {
                if (product.getStockQuantity() > 0) {
                    int remaining = product.getStockQuantity() - quantity;
                    product.setStockQuantity(Math.max(0, remaining));
                    if (remaining <= 0) {
                        product.setInStock(false);
                        notifyOutOfStock(product);
                    }
                    product.setUpdatedAt(LocalDateTime.now());
                    productRepository.save(product);
                }
            });
        }

        Invoice invoice = new Invoice();
        invoice.setCustomerId(user.getId());
        invoice.setCustomerName(user.getFullName());
        invoice.setAmount(totalKobo);
        invoice.setCurrency("NGN");
        invoice.setStatus("pending");
        invoice.setDescription(description.toString().replaceAll(", $", ""));
        invoice.setDueDate(LocalDateTime.now().plusDays(1));
        invoice.setCreatedAt(LocalDateTime.now());
        Invoice savedInvoice = invoiceRepository.save(invoice);

        Map<String, Object> paymentBody = new LinkedHashMap<>();
        paymentBody.put("amount", totalKobo);
        paymentBody.put("description", savedInvoice.getDescription());
        paymentBody.put("invoiceId", savedInvoice.getId());

        Map<String, Object> payment = "flutterwave".equalsIgnoreCase(provider)
                ? initializeFlutterwave(userId, paymentBody)
                : initializePaystack(userId, paymentBody);

        Map<String, Object> result = new LinkedHashMap<>(payment);
        result.put("invoiceId", savedInvoice.getId());
        result.put("totalKobo", totalKobo);
        result.put("totalNaira", totalKobo / 100);
        result.put("originalTotalKobo", originalTotalKobo);
        result.put("staffDiscountPercent", discount.percent());
        result.put("staffDiscountKobo", Math.max(0, originalTotalKobo - totalKobo));

        notificationService.create(user.getId(), "Checkout started",
                "Your order of ₦" + String.format("%,d", totalKobo / 100) + " is being processed.", "info");

        return result;
    }

    private record StaffDiscount(int percent, String label) {
        long amountKobo(long totalKobo) {
            return Math.round(totalKobo * (percent / 100.0));
        }
    }

    private boolean isCustomer(User user) {
        return user != null && "CUSTOMER".equalsIgnoreCase(user.getRole());
    }

    private boolean isStaff(User user) {
        if (user == null || user.getRole() == null) {
            return false;
        }
        String role = user.getRole().toUpperCase();
        return "ADMIN".equals(role) || "SUPERVISOR".equals(role)
                || "SALES_AGENT".equals(role) || "SUPPORT_AGENT".equals(role);
    }

    private StaffDiscount staffDiscountFor(User user) {
        if (!isStaff(user) || user.getCreatedAt() == null) {
            return new StaffDiscount(0, null);
        }
        long months = java.time.temporal.ChronoUnit.MONTHS.between(
                user.getCreatedAt().toLocalDate(),
                java.time.LocalDate.now());
        if (months >= 12) {
            return new StaffDiscount(15, "1+ year staff discount");
        }
        if (months >= 6) {
            return new StaffDiscount(10, "6+ month staff discount");
        }
        return new StaffDiscount(0, null);
    }

    private int parseQuantity(Object value) {
        if (value == null) return 1;
        int qty = value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
        if (qty < 1) throw new RuntimeException("Invalid quantity");
        return qty;
    }

    private long resolveUnitPrice(Map<String, Object> item, Product product) {
        Object raw = item.get("unitPrice");
        if (raw == null) {
            return product.getPrice();
        }
        long unitPrice = raw instanceof Number number ? number.longValue() : Long.parseLong(raw.toString());
        if (unitPrice <= 0) {
            throw new RuntimeException("Invalid price for " + product.getName());
        }
        long catalogPrice = product.getPrice();
        long maxAllowed = product.getOriginalPrice() != null && product.getOriginalPrice() > catalogPrice
                ? product.getOriginalPrice()
                : catalogPrice;
        if (unitPrice > maxAllowed) {
            throw new RuntimeException("Invalid discounted price for " + product.getName());
        }
        return unitPrice;
    }

    public Map<String, Object> initializePaystack(String userId, Map<String, Object> body) {
        User user = requestUserService.requireUser(userId);
        long amount = parseAmount(body.get("amount"));
        String description = stringVal(body.get("description"), "CyForce payment");
        String invoiceId = stringVal(body.get("invoiceId"), null);
        String reference = "PSK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();

        PaymentTransaction tx = new PaymentTransaction();
        tx.setReference(reference);
        tx.setProvider("paystack");
        tx.setUserId(user.getId());
        tx.setUserEmail(user.getEmail());
        tx.setAmount(amount);
        tx.setCurrency("NGN");
        tx.setStatus("pending");
        tx.setDescription(description);
        tx.setMetadata(Map.of("invoiceId", invoiceId != null ? invoiceId : ""));
        tx.setCreatedAt(LocalDateTime.now());
        transactionRepository.save(tx);

        String callbackUrl = properties.getCallbackBaseUrl() + "/payment/callback?provider=paystack&reference=" + reference;

        if (!hasPaystackKey()) {
            tx.setAuthorizationUrl(callbackUrl + "&autoComplete=1");
            transactionRepository.save(tx);
            return Map.of(
                    "provider", "paystack",
                    "reference", reference,
                    "authorizationUrl", tx.getAuthorizationUrl(),
                    "publicKey", properties.getPaystack().getPublicKey(),
                    "amount", amount,
                    "autoComplete", true,
                    "message", "Payment will be completed on return"
            );
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("email", user.getEmail());
            payload.put("amount", amount);
            payload.put("reference", reference);
            payload.put("currency", "NGN");
            payload.put("callback_url", callbackUrl);
            payload.put("metadata", Map.of("userId", user.getId(), "invoiceId", invoiceId != null ? invoiceId : ""));

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(properties.getPaystack().getBaseUrl() + "/transaction/initialize")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getPaystack().getSecretKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(Map.class);

            String authUrl = extractNested(response, "data", "authorization_url");
            tx.setAuthorizationUrl(authUrl);
            transactionRepository.save(tx);

            return Map.of(
                    "provider", "paystack",
                    "reference", reference,
                    "authorizationUrl", authUrl != null ? authUrl : callbackUrl,
                    "publicKey", properties.getPaystack().getPublicKey(),
                    "amount", amount
            );
        } catch (Exception e) {
            log.error("Paystack init failed: {}", e.getMessage());
            throw new RuntimeException("Paystack initialization failed: " + e.getMessage());
        }
    }

    public Map<String, Object> initializeFlutterwave(String userId, Map<String, Object> body) {
        User user = requestUserService.requireUser(userId);
        long amount = parseAmount(body.get("amount"));
        String description = stringVal(body.get("description"), "CyForce payment");
        String invoiceId = stringVal(body.get("invoiceId"), null);
        String reference = "FLW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();

        PaymentTransaction tx = new PaymentTransaction();
        tx.setReference(reference);
        tx.setProvider("flutterwave");
        tx.setUserId(user.getId());
        tx.setUserEmail(user.getEmail());
        tx.setAmount(amount);
        tx.setCurrency("NGN");
        tx.setStatus("pending");
        tx.setDescription(description);
        tx.setMetadata(Map.of("invoiceId", invoiceId != null ? invoiceId : ""));
        tx.setCreatedAt(LocalDateTime.now());
        transactionRepository.save(tx);

        String redirectUrl = properties.getCallbackBaseUrl() + "/payment/callback?provider=flutterwave&reference=" + reference;

        if (!hasFlutterwaveKey()) {
            tx.setAuthorizationUrl(redirectUrl + "&autoComplete=1");
            transactionRepository.save(tx);
            return Map.of(
                    "provider", "flutterwave",
                    "reference", reference,
                    "authorizationUrl", tx.getAuthorizationUrl(),
                    "publicKey", properties.getFlutterwave().getPublicKey(),
                    "amount", amount,
                    "autoComplete", true,
                    "message", "Payment will be completed on return"
            );
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tx_ref", reference);
            payload.put("amount", amount / 100.0);
            payload.put("currency", "NGN");
            payload.put("redirect_url", redirectUrl);
            payload.put("payment_options", "card,ussd,banktransfer");
            payload.put("customer", Map.of(
                    "email", user.getEmail(),
                    "name", user.getFullName() != null ? user.getFullName() : user.getEmail()
            ));
            payload.put("customizations", Map.of(
                    "title", "CyForce",
                    "description", description
            ));
            payload.put("meta", Map.of("userId", user.getId(), "invoiceId", invoiceId != null ? invoiceId : ""));

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(properties.getFlutterwave().getBaseUrl() + "/payments")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getFlutterwave().getSecretKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(Map.class);

            String authUrl = extractNested(response, "data", "link");
            tx.setAuthorizationUrl(authUrl);
            transactionRepository.save(tx);

            return Map.of(
                    "provider", "flutterwave",
                    "reference", reference,
                    "authorizationUrl", authUrl != null ? authUrl : redirectUrl,
                    "publicKey", properties.getFlutterwave().getPublicKey(),
                    "amount", amount
            );
        } catch (Exception e) {
            log.error("Flutterwave init failed: {}", e.getMessage());
            throw new RuntimeException("Flutterwave initialization failed: " + e.getMessage());
        }
    }

    public PaymentTransaction verifyPaystack(String reference) {
        PaymentTransaction tx = transactionRepository.findByReference(reference)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

        if ("success".equals(tx.getStatus())) {
            return tx;
        }

        if (!hasPaystackKey()) {
            return markSuccess(tx, reference, "local-verify");
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(properties.getPaystack().getBaseUrl() + "/transaction/verify/" + reference)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getPaystack().getSecretKey())
                    .retrieve()
                    .body(Map.class);

            String status = extractNested(response, "data", "status");
            String providerRef = extractNested(response, "data", "id");
            if ("success".equalsIgnoreCase(status)) {
                return markSuccess(tx, reference, providerRef);
            }
            tx.setStatus("failed");
            transactionRepository.save(tx);
            notifyPaymentFailed(tx);
            return tx;
        } catch (Exception e) {
            log.error("Paystack verify failed: {}", e.getMessage());
            throw new RuntimeException("Paystack verification failed: " + e.getMessage());
        }
    }

    public PaymentTransaction verifyFlutterwave(String reference) {
        PaymentTransaction tx = transactionRepository.findByReference(reference)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

        if ("success".equals(tx.getStatus())) {
            return tx;
        }

        if (!hasFlutterwaveKey()) {
            return markSuccess(tx, reference, "local-verify");
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(properties.getFlutterwave().getBaseUrl() + "/transactions/verify_by_reference?tx_ref=" + reference)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getFlutterwave().getSecretKey())
                    .retrieve()
                    .body(Map.class);

            String status = extractNested(response, "data", "status");
            String txId = extractNested(response, "data", "id");
            if ("successful".equalsIgnoreCase(status)) {
                return markSuccess(tx, reference, txId != null ? txId : reference);
            }
            tx.setStatus("failed");
            transactionRepository.save(tx);
            notifyPaymentFailed(tx);
            return tx;
        } catch (Exception e) {
            log.error("Flutterwave verify failed: {}", e.getMessage());
            throw new RuntimeException("Flutterwave verification failed: " + e.getMessage());
        }
    }

    public PaymentTransaction completePayment(String reference) {
        PaymentTransaction tx = transactionRepository.findByReference(reference)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
        if ("flutterwave".equalsIgnoreCase(tx.getProvider())) {
            return verifyFlutterwave(reference);
        }
        return verifyPaystack(reference);
    }

    public void handlePaystackWebhook(Map<String, Object> payload) {
        String event = stringVal(payload.get("event"), "");
        if (!"charge.success".equals(event)) return;

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        if (data == null) return;

        String reference = stringVal(data.get("reference"), null);
        if (reference == null) return;

        transactionRepository.findByReference(reference).ifPresent(tx -> {
            if (!"success".equals(tx.getStatus())) {
                markSuccess(tx, reference, stringVal(data.get("id"), null));
            }
        });
    }

    public void handleFlutterwaveWebhook(Map<String, Object> payload) {
        String status = stringVal(payload.get("status"), "");
        if (!"successful".equalsIgnoreCase(status)) return;

        String reference = stringVal(payload.get("txRef"), null);
        if (reference == null) return;

        transactionRepository.findByReference(reference).ifPresent(tx -> {
            tx.setProviderReference(stringVal(payload.get("id"), tx.getProviderReference()));
            if (!"success".equals(tx.getStatus())) {
                markSuccess(tx, reference, tx.getProviderReference());
            }
        });
    }

    public List<PaymentTransaction> userTransactions(String userId) {
        requestUserService.requireUser(userId);
        return transactionRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<Invoice> userInvoices(String userId) {
        User user = requestUserService.requireUser(userId);
        return invoiceRepository.findByCustomerIdOrderByCreatedAtDesc(user.getId());
    }

    public Map<String, Object> billingOverview(String userId) {
        User user = requestUserService.requireUser(userId);
        boolean isCustomer = "CUSTOMER".equalsIgnoreCase(user.getRole());
        boolean isStaff = List.of("ADMIN", "SUPERVISOR", "SALES_AGENT", "SUPPORT_AGENT")
                .contains(user.getRole() != null ? user.getRole().toUpperCase() : "");
        if (!isCustomer && !isStaff) {
            throw new RuntimeException("Billing is not available for this account");
        }
        List<Invoice> invoices = isCustomer ? userInvoices(userId) : List.of();
        List<PaymentTransaction> txs = userTransactions(userId);
        long pending = invoices.stream().filter(i -> "pending".equals(i.getStatus()) || "unpaid".equals(i.getStatus())).count();
        long paid = invoices.stream().filter(i -> "paid".equals(i.getStatus())).count();
        long revenue = invoices.stream().filter(i -> "paid".equals(i.getStatus())).mapToLong(Invoice::getAmount).sum();
        if (isStaff) {
            paid = txs.stream().filter(t -> "success".equalsIgnoreCase(t.getStatus())).count();
            revenue = txs.stream().filter(t -> "success".equalsIgnoreCase(t.getStatus())).mapToLong(PaymentTransaction::getAmount).sum();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("monthlyRevenue", revenue);
        result.put("activePlans", paid);
        result.put("pendingInvoices", pending);
        result.put("invoices", invoices);
        result.put("transactions", txs);
        result.put("staffPurchases", isStaff);
        return result;
    }

    private PaymentTransaction markSuccess(PaymentTransaction tx, String reference, String providerRef) {
        tx.setStatus("success");
        tx.setProviderReference(providerRef);
        tx.setVerifiedAt(LocalDateTime.now());
        PaymentTransaction saved = transactionRepository.save(tx);

        String invoiceId = tx.getMetadata() != null ? stringVal(tx.getMetadata().get("invoiceId"), null) : null;
        if (invoiceId != null && !invoiceId.isBlank()) {
            invoiceRepository.findById(invoiceId).ifPresent(inv -> {
                inv.setStatus("paid");
                inv.setPaidAt(LocalDateTime.now());
                inv.setPaymentTransactionId(saved.getId());
                if (inv.getSurveyToken() == null || inv.getSurveyToken().isBlank()) {
                    inv.setSurveyToken(UUID.randomUUID().toString().replace("-", ""));
                }
                invoiceRepository.save(inv);

                String amountText = inv.getAmount() > 0
                        ? "₦" + String.format("%,d", inv.getAmount() / 100)
                        : "";
                String surveyUrl = "http://localhost:3000/survey/purchase/" + inv.getSurveyToken();

                if (tx.getUserId() != null) {
                    userRepository.findById(tx.getUserId()).ifPresent(user -> {
                        if (user.getEmail() != null && !user.getEmail().isBlank()) {
                            try {
                                emailService.sendPurchaseConfirmationEmail(
                                        user.getEmail(),
                                        user.getFullName(),
                                        amountText.isBlank() ? "your order" : amountText,
                                        inv.getDescription(),
                                        surveyUrl
                                );
                            } catch (RuntimeException e) {
                                log.warn("Purchase confirmation email failed: {}", e.getMessage());
                            }
                        }
                    });
                }

                if (tx.getUserId() != null) {
                    notificationService.create(tx.getUserId(), "Purchase confirmed",
                            "Payment successful" + (amountText.isBlank() ? "" : " — " + amountText)
                                    + ". Please rate your experience: " + surveyUrl,
                            "success");
                }

                if (inv.getConversationId() != null) {
                    conversationRepository.findById(inv.getConversationId()).ifPresent(conv -> {
                        if (conv.getCustomerRating() == null || conv.getCustomerRating() <= 0) {
                            conv.setStatus("pending_rating");
                            conv.setClosedAt(LocalDateTime.now());
                            conv.setCloseReason("purchase_completed");
                        } else {
                            conv.setStatus("closed");
                        }
                        conv.setUpdatedAt(LocalDateTime.now());
                        conversationRepository.save(conv);
                    });
                }

                if (inv.getSalesAgentId() != null) {
                    notificationService.create(inv.getSalesAgentId(), "Deal closed",
                            (inv.getCustomerName() != null ? inv.getCustomerName() : "Customer")
                                    + " paid your invoice" + (amountText.isBlank() ? "" : " of " + amountText) + ".",
                            "success");
                }
            });
        }

        if (tx.getUserId() != null && tx.getProvider() != null) {
            userRepository.findById(tx.getUserId()).ifPresent(user -> {
                user.setPreferredPaymentMethod(tx.getProvider().toLowerCase());
                user.setUpdatedAt(LocalDateTime.now());
                userRepository.save(user);
            });
        }

        if (tx.getUserId() != null) {
            String amount = tx.getAmount() > 0
                    ? "₦" + String.format("%,d", tx.getAmount() / 100)
                    : "";
            if (invoiceId == null || invoiceId.isBlank()) {
                notificationService.create(tx.getUserId(), "Payment successful",
                        "Your payment" + (amount.isBlank() ? "" : " of " + amount) + " was completed successfully.", "success");
            }
        }

        return saved;
    }

    public void notifyCheckoutFailed(String userId, String reason) {
        if (userId == null || userId.isBlank()) return;
        notificationService.create(userId, "Checkout failed",
                reason == null || reason.isBlank() ? "We could not complete your checkout. Please try again." : reason,
                "error");
    }

    private void notifyPaymentFailed(PaymentTransaction tx) {
        if (tx.getUserId() == null) return;
        notificationService.create(tx.getUserId(), "Payment failed",
                "Your payment could not be completed. Please try again or use a different method.", "error");
    }

    private void notifyOutOfStock(com.cyforce.model.Product product) {
        String message = product.getName() + " is now out of stock.";
        userRepository.findAll().stream()
                .filter(u -> {
                    String role = u.getRole() == null ? "" : u.getRole().toUpperCase();
                    return u.isActive() && ("ADMIN".equals(role) || "SALES_AGENT".equals(role) || "SUPERVISOR".equals(role));
                })
                .forEach(u -> notificationService.create(u.getId(), "Product out of stock", message, "warning"));
    }

    private boolean hasPaystackKey() {
        String key = properties.getPaystack().getSecretKey();
        return key != null && !key.isBlank() && !key.contains("placeholder");
    }

    private boolean hasFlutterwaveKey() {
        String key = properties.getFlutterwave().getSecretKey();
        return key != null && !key.isBlank() && !key.contains("placeholder");
    }

    private long parseAmount(Object value) {
        if (value == null) throw new RuntimeException("Amount is required");
        if (value instanceof Number n) {
            long amount = n.longValue();
            if (amount < 100) amount *= 100;
            return amount;
        }
        long amount = Long.parseLong(value.toString());
        if (amount < 100) amount *= 100;
        return amount;
    }

    private String stringVal(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    @SuppressWarnings("unchecked")
    private String extractNested(Map<String, Object> map, String... keys) {
        Object current = map;
        for (String key : keys) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(key);
        }
        return current == null ? null : current.toString();
    }
}
