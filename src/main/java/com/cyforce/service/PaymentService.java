package com.cyforce.service;

import com.cyforce.config.PaymentProperties;
import com.cyforce.model.Invoice;
import com.cyforce.model.PaymentTransaction;
import com.cyforce.model.Product;
import com.cyforce.model.User;
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
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final RequestUserService requestUserService;
    private final RestClient restClient;

    public PaymentService(PaymentProperties properties,
                          PaymentTransactionRepository transactionRepository,
                          InvoiceRepository invoiceRepository,
                          ProductRepository productRepository,
                          UserRepository userRepository,
                          NotificationService notificationService,
                          RequestUserService requestUserService) {
        this.properties = properties;
        this.transactionRepository = transactionRepository;
        this.invoiceRepository = invoiceRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.requestUserService = requestUserService;
        this.restClient = RestClient.create();
    }

    public Map<String, Object> checkoutCart(String userId, Map<String, Object> body) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "CUSTOMER");

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
            long lineKobo = product.getPrice() * 100L * quantity;
            totalKobo += lineKobo;
            description.append(product.getName()).append(" x").append(quantity).append(", ");
        }

        if (totalKobo <= 0) {
            throw new RuntimeException("Invalid cart total");
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

        notificationService.create(user.getId(), "Checkout started",
                "Your order of ₦" + String.format("%,d", totalKobo / 100) + " is being processed.", "info");

        return result;
    }

    private int parseQuantity(Object value) {
        if (value == null) return 1;
        int qty = value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
        if (qty < 1) throw new RuntimeException("Invalid quantity");
        return qty;
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
            tx.setAuthorizationUrl(callbackUrl + "&sandbox=1");
            transactionRepository.save(tx);
            return Map.of(
                    "provider", "paystack",
                    "reference", reference,
                    "authorizationUrl", tx.getAuthorizationUrl(),
                    "publicKey", properties.getPaystack().getPublicKey(),
                    "amount", amount,
                    "sandbox", true,
                    "message", "Configure payment.paystack.secret-key for live sandbox initialization"
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
            tx.setAuthorizationUrl(redirectUrl + "&sandbox=1");
            transactionRepository.save(tx);
            return Map.of(
                    "provider", "flutterwave",
                    "reference", reference,
                    "authorizationUrl", tx.getAuthorizationUrl(),
                    "publicKey", properties.getFlutterwave().getPublicKey(),
                    "amount", amount,
                    "sandbox", true,
                    "message", "Configure payment.flutterwave.secret-key for live sandbox initialization"
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
            return markSuccess(tx, reference, "sandbox-verify");
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
            return markSuccess(tx, reference, "sandbox-verify");
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
        List<Invoice> invoices = userInvoices(userId);
        List<PaymentTransaction> txs = userTransactions(userId);
        long pending = invoices.stream().filter(i -> "pending".equals(i.getStatus()) || "unpaid".equals(i.getStatus())).count();
        long paid = invoices.stream().filter(i -> "paid".equals(i.getStatus())).count();
        long revenue = invoices.stream().filter(i -> "paid".equals(i.getStatus())).mapToLong(Invoice::getAmount).sum();
        return Map.of(
                "monthlyRevenue", revenue,
                "activePlans", paid,
                "pendingInvoices", pending,
                "invoices", invoices,
                "transactions", txs
        );
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
                invoiceRepository.save(inv);
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
            notificationService.create(tx.getUserId(), "Payment successful",
                    "Your payment" + (amount.isBlank() ? "" : " of " + amount) + " was completed successfully.", "success");
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
