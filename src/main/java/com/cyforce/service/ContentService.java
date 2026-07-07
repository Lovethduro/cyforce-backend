package com.cyforce.service;

import com.cyforce.model.HotDeal;
import com.cyforce.model.MotivationalMessage;
import com.cyforce.model.Product;
import com.cyforce.model.User;
import com.cyforce.repository.HotDealRepository;
import com.cyforce.repository.MotivationalMessageRepository;
import com.cyforce.repository.ProductRepository;
import com.cyforce.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ContentService {

    private static final Map<String, List<String>> DEFAULT_MESSAGES = Map.of(
            "ADMIN", List.of(
                    "Great leaders create more leaders — keep empowering your team.",
                    "Your oversight keeps CyForce running smoothly. Stay sharp today.",
                    "Every system you tune helps the whole company perform better."
            ),
            "SUPERVISOR", List.of(
                    "Coach your team today — small guidance creates big wins.",
                    "Review the pipeline and celebrate progress, not just results.",
                    "Your support turns good agents into top performers."
            ),
            "SALES_AGENT", List.of(
                    "Follow up with hot leads within 24 hours to boost conversion.",
                    "Personalize your outreach — mention the prospect's company by name.",
                    "Every conversation is a chance to solve a real problem for a customer.",
                    "Focus on qualified leads with scores above 70."
            ),
            "SUPPORT_AGENT", List.of(
                    "A quick, empathetic reply can turn frustration into loyalty.",
                    "Document solutions — your notes help the whole team.",
                    "You're the voice of CyForce when customers need help most."
            ),
            "CUSTOMER", List.of(
                    "Welcome back — we're here whenever you need us.",
                    "Explore our latest offers and find the right solution for you.",
                    "Your success is our mission. Reach out anytime."
            )
    );

    private final HotDealRepository hotDealRepository;
    private final MotivationalMessageRepository motivationalMessageRepository;
    private final UserRepository userRepository;
    private final RequestUserService requestUserService;
    private final FileStorageService fileStorageService;
    private final NotificationService notificationService;
    private final ProductRepository productRepository;

    public ContentService(HotDealRepository hotDealRepository,
                          MotivationalMessageRepository motivationalMessageRepository,
                          UserRepository userRepository,
                          RequestUserService requestUserService,
                          FileStorageService fileStorageService,
                          NotificationService notificationService,
                          ProductRepository productRepository) {
        this.hotDealRepository = hotDealRepository;
        this.motivationalMessageRepository = motivationalMessageRepository;
        this.userRepository = userRepository;
        this.requestUserService = requestUserService;
        this.fileStorageService = fileStorageService;
        this.notificationService = notificationService;
        this.productRepository = productRepository;
    }

    public List<Map<String, Object>> activeHotDeals() {
        processHotDealSchedule();
        LocalDateTime now = LocalDateTime.now();
        return hotDealRepository.findAll().stream()
                .filter(deal -> isLiveNow(deal, now))
                .sorted((a, b) -> {
                    LocalDateTime left = a.getCreatedAt() != null ? a.getCreatedAt() : LocalDateTime.MIN;
                    LocalDateTime right = b.getCreatedAt() != null ? b.getCreatedAt() : LocalDateTime.MIN;
                    return right.compareTo(left);
                })
                .map(this::toHotDealView)
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> allHotDeals(String userId) {
        requireHotDealManager(userId);
        processHotDealSchedule();
        return hotDealRepository.findAll().stream()
                .sorted((a, b) -> {
                    LocalDateTime left = a.getCreatedAt() != null ? a.getCreatedAt() : LocalDateTime.MIN;
                    LocalDateTime right = b.getCreatedAt() != null ? b.getCreatedAt() : LocalDateTime.MIN;
                    return right.compareTo(left);
                })
                .map(this::toHotDealView)
                .collect(Collectors.toList());
    }

    public Map<String, Object> createHotDeal(String userId, Map<String, String> fields, MultipartFile image) {
        User creator = requireHotDealManager(userId);
        String title = fields.get("title") != null ? fields.get("title").trim() : "";
        if (title.isBlank()) {
            throw new RuntimeException("Title is required");
        }
        HotDeal deal = new HotDeal();
        applyHotDealFields(deal, fields);
        deal.setTitle(title);
        String productId = fields.get("productId") != null ? fields.get("productId").trim() : "";
        if (productId.isBlank()) {
            throw new RuntimeException("Please select a product for this hot deal");
        }
        resolveLinkedProduct(productId);
        deal.setProductId(productId);
        deal.setImageUrl(fileStorageService.storeHotDealImage(image));
        deal.setCreatedById(creator.getId());
        deal.setCreatedByName(creator.getFullName());
        deal.setActive(parseBooleanField(fields.get("active"), true));
        if (deal.getCtaLink() == null || deal.getCtaLink().isBlank()) {
            deal.setCtaLink("/customer/products");
        }
        deal.setCreatedAt(LocalDateTime.now());
        deal.setUpdatedAt(LocalDateTime.now());
        validateHotDealSchedule(deal);
        HotDeal saved = hotDealRepository.save(deal);
        syncHotDealState(saved);
        saved = hotDealRepository.findById(saved.getId()).orElse(saved);
        return toHotDealView(saved);
    }

    public Map<String, Object> updateHotDeal(String userId, String dealId, Map<String, String> fields, MultipartFile image) {
        requireHotDealManager(userId);
        HotDeal deal = hotDealRepository.findById(dealId)
                .orElseThrow(() -> new RuntimeException("Hot deal not found"));
        applyHotDealFields(deal, fields);
        if (fields.containsKey("active")) {
            deal.setActive(parseBooleanField(fields.get("active"), deal.isActive()));
        }
        if (image != null && !image.isEmpty()) {
            fileStorageService.deleteIfStored(deal.getImageUrl());
            deal.setImageUrl(fileStorageService.storeHotDealImage(image));
        }
        validateHotDealSchedule(deal);
        deal.setUpdatedAt(LocalDateTime.now());
        HotDeal saved = hotDealRepository.save(deal);
        syncHotDealState(saved);
        saved = hotDealRepository.findById(saved.getId()).orElse(saved);
        return toHotDealView(saved);
    }

    public void deleteHotDeal(String userId, String dealId) {
        User admin = requestUserService.requireUser(userId);
        requestUserService.requireRole(admin, "ADMIN");
        HotDeal deal = hotDealRepository.findById(dealId)
                .orElseThrow(() -> new RuntimeException("Hot deal not found"));
        revertDealPricing(deal);
        fileStorageService.deleteIfStored(deal.getImageUrl());
        hotDealRepository.deleteById(dealId);
    }

    public void processHotDealSchedule() {
        LocalDateTime now = LocalDateTime.now();
        for (HotDeal deal : hotDealRepository.findAll()) {
            syncHotDealState(deal, now);
        }
    }

    public Map<String, Object> motivationalForRole(String role) {
        return motivationalForUser(null, role);
    }

    public Map<String, Object> motivationalForUser(String userId, String role) {
        if (userId != null && !userId.isBlank()) {
            User user = userRepository.findById(userId).orElse(null);
            if (user != null && !user.wantsMotivationalMessages()) {
                Map<String, Object> disabled = new LinkedHashMap<>();
                disabled.put("enabled", false);
                disabled.put("message", "");
                return disabled;
            }
        }

        String normalizedRole = role == null ? "CUSTOMER" : role.toUpperCase();
        List<String> pool = new ArrayList<>();

        motivationalMessageRepository.findByActiveTrueOrderByCreatedAtDesc().stream()
                .filter(msg -> msg.getRoles() == null || msg.getRoles().isEmpty()
                        || msg.getRoles().stream().anyMatch(r -> normalizedRole.equalsIgnoreCase(r)
                        || "ALL".equalsIgnoreCase(r)))
                .map(MotivationalMessage::getMessage)
                .forEach(pool::add);

        pool.addAll(DEFAULT_MESSAGES.getOrDefault(normalizedRole, DEFAULT_MESSAGES.get("CUSTOMER")));

        int index = Math.floorMod(LocalDate.now().getDayOfYear() + normalizedRole.hashCode(), pool.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", normalizedRole);
        result.put("message", pool.get(index));
        result.put("index", index);
        result.put("total", pool.size());
        result.put("enabled", true);
        return result;
    }

    public List<Map<String, Object>> listMotivationalMessages(String userId) {
        requireHotDealManager(userId);
        return motivationalMessageRepository.findAll().stream()
                .sorted((a, b) -> {
                    LocalDateTime left = a.getCreatedAt() != null ? a.getCreatedAt() : LocalDateTime.MIN;
                    LocalDateTime right = b.getCreatedAt() != null ? b.getCreatedAt() : LocalDateTime.MIN;
                    return right.compareTo(left);
                })
                .map(msg -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", msg.getId());
                    row.put("message", msg.getMessage());
                    row.put("roles", msg.getRoles());
                    row.put("active", msg.isActive());
                    row.put("createdByName", msg.getCreatedByName());
                    row.put("createdAt", msg.getCreatedAt());
                    return row;
                })
                .collect(Collectors.toList());
    }

    public Map<String, Object> createMotivationalMessage(String userId, Map<String, Object> body) {
        User creator = requireHotDealManager(userId);
        String message = body.get("message") != null ? body.get("message").toString().trim() : "";
        if (message.isBlank()) {
            throw new RuntimeException("Message is required");
        }
        MotivationalMessage entry = new MotivationalMessage();
        entry.setMessage(message);
        entry.setRoles(parseRoles(body.get("roles")));
        entry.setActive(body.get("active") == null || Boolean.parseBoolean(body.get("active").toString()));
        entry.setCreatedById(creator.getId());
        entry.setCreatedByName(creator.getFullName());
        entry.setCreatedAt(LocalDateTime.now());
        MotivationalMessage saved = motivationalMessageRepository.save(entry);
        return Map.of(
                "id", saved.getId(),
                "message", saved.getMessage(),
                "roles", saved.getRoles(),
                "active", saved.isActive()
        );
    }

    private User requireHotDealManager(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "ADMIN", "SUPERVISOR");
        return user;
    }

    private void applyHotDealFields(HotDeal deal, Map<String, String> fields) {
        if (fields.get("title") != null && !fields.get("title").isBlank()) {
            deal.setTitle(fields.get("title").trim());
        }
        if (fields.containsKey("description")) {
            deal.setDescription(fields.get("description") != null ? fields.get("description").trim() : null);
        }
        if (fields.get("badge") != null) {
            deal.setBadge(fields.get("badge").trim());
        }
        if (fields.get("price") != null && !fields.get("price").isBlank()) {
            deal.setPrice(Long.parseLong(fields.get("price").trim()));
        } else if (fields.containsKey("price")) {
            deal.setPrice(null);
        }
        if (fields.get("originalPrice") != null && !fields.get("originalPrice").isBlank()) {
            deal.setOriginalPrice(Long.parseLong(fields.get("originalPrice").trim()));
        } else if (fields.containsKey("originalPrice")) {
            deal.setOriginalPrice(null);
        }
        if (fields.get("discountPercent") != null && !fields.get("discountPercent").isBlank()) {
            deal.setDiscountPercent(Integer.parseInt(fields.get("discountPercent").trim()));
        } else if (deal.getOriginalPrice() != null && deal.getPrice() != null && deal.getOriginalPrice() > deal.getPrice()) {
            deal.setDiscountPercent((int) Math.round((1.0 - (deal.getPrice() * 1.0 / deal.getOriginalPrice())) * 100));
        }
        if (fields.get("productId") != null && !fields.get("productId").isBlank()) {
            String productId = fields.get("productId").trim();
            resolveLinkedProduct(productId);
            deal.setProductId(productId);
        }
        if (fields.get("ctaLabel") != null) {
            deal.setCtaLabel(fields.get("ctaLabel").trim());
        }
        if (fields.get("ctaLink") != null) {
            String link = fields.get("ctaLink").trim();
            deal.setCtaLink(link.isBlank() ? "/customer/products" : link);
        }
        if (fields.containsKey("startsAt")) {
            deal.setStartsAt(parseDateTimeField(fields.get("startsAt")));
        }
        if (fields.containsKey("expiresAt")) {
            deal.setExpiresAt(parseDateTimeField(fields.get("expiresAt")));
        }
    }

    private LocalDateTime parseDateTimeField(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() == 16) {
            value = value + ":00";
        }
        return LocalDateTime.parse(value);
    }

    private void validateHotDealSchedule(HotDeal deal) {
        if (!deal.isActive()) {
            return;
        }
        if (deal.getStartsAt() == null || deal.getExpiresAt() == null) {
            throw new RuntimeException("Published hot deals need a start and end date/time");
        }
        if (!deal.getExpiresAt().isAfter(deal.getStartsAt())) {
            throw new RuntimeException("Hot deal end time must be after the start time");
        }
    }

    private boolean isLiveNow(HotDeal deal, LocalDateTime now) {
        if (!deal.isActive()) {
            return false;
        }
        if (deal.getStartsAt() != null && deal.getStartsAt().isAfter(now)) {
            return false;
        }
        if (deal.getExpiresAt() != null && !deal.getExpiresAt().isAfter(now)) {
            return false;
        }
        return true;
    }

    private boolean isExpired(HotDeal deal, LocalDateTime now) {
        return deal.getExpiresAt() != null && !deal.getExpiresAt().isAfter(now);
    }

    private void syncHotDealState(HotDeal deal) {
        syncHotDealState(deal, LocalDateTime.now());
    }

    private void syncHotDealState(HotDeal deal, LocalDateTime now) {
        if (!deal.isActive()) {
            if (deal.isPriceApplied()) {
                revertDealPricing(deal);
            }
            return;
        }

        if (isExpired(deal, now)) {
            deal.setActive(false);
            revertDealPricing(deal);
            deal.setUpdatedAt(now);
            hotDealRepository.save(deal);
            return;
        }

        if (isLiveNow(deal, now)) {
            applyDealPricing(deal);
            if (!deal.isPromoNotified()) {
                notifyCustomersOfHotDeal(deal);
                deal.setPromoNotified(true);
                deal.setUpdatedAt(now);
                hotDealRepository.save(deal);
            }
            return;
        }

        if (deal.isPriceApplied()) {
            revertDealPricing(deal);
        }
    }

    private void applyDealPricing(HotDeal deal) {
        if (deal.getProductId() == null || deal.getPrice() == null) {
            return;
        }
        Product product = productRepository.findById(deal.getProductId()).orElse(null);
        if (product == null) {
            return;
        }
        if (!deal.isPriceApplied()) {
            deal.setCatalogPriceBeforeDeal(product.getPrice());
            if (deal.getOriginalPrice() == null) {
                deal.setOriginalPrice(product.getPrice());
            }
            deal.setPriceApplied(true);
        }
        long catalogPrice = deal.getOriginalPrice() != null
                ? deal.getOriginalPrice()
                : deal.getCatalogPriceBeforeDeal() != null ? deal.getCatalogPriceBeforeDeal() : product.getPrice();
        product.setPrice(deal.getPrice());
        product.setOriginalPrice(catalogPrice);
        product.setUpdatedAt(LocalDateTime.now());
        productRepository.save(product);
        deal.setUpdatedAt(LocalDateTime.now());
        hotDealRepository.save(deal);
    }

    private void revertDealPricing(HotDeal deal) {
        if (!deal.isPriceApplied() || deal.getProductId() == null) {
            deal.setPriceApplied(false);
            return;
        }
        productRepository.findById(deal.getProductId()).ifPresent(product -> {
            long restorePrice = deal.getCatalogPriceBeforeDeal() != null
                    ? deal.getCatalogPriceBeforeDeal()
                    : deal.getOriginalPrice() != null ? deal.getOriginalPrice() : product.getPrice();
            product.setPrice(restorePrice);
            if (product.getOriginalPrice() != null && product.getOriginalPrice().equals(restorePrice)) {
                product.setOriginalPrice(null);
            }
            product.setUpdatedAt(LocalDateTime.now());
            productRepository.save(product);
        });
        deal.setPriceApplied(false);
        deal.setUpdatedAt(LocalDateTime.now());
        hotDealRepository.save(deal);
    }

    private String hotDealScheduleStatus(HotDeal deal, LocalDateTime now) {
        if (!deal.isActive()) {
            return isExpired(deal, now) ? "expired" : "hidden";
        }
        if (deal.getStartsAt() != null && deal.getStartsAt().isAfter(now)) {
            return "scheduled";
        }
        if (isExpired(deal, now)) {
            return "expired";
        }
        return "live";
    }

    private Product resolveLinkedProduct(String productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Selected product was not found"));
        if (!product.isActive()) {
            throw new RuntimeException("Selected product is not active in the catalog");
        }
        return product;
    }

    private void notifyCustomersOfHotDeal(HotDeal deal) {
        String amount = deal.getPrice() != null && deal.getPrice() > 0
                ? " — ₦" + String.format("%,d", deal.getPrice())
                : "";
        String message = (deal.getTitle() != null ? deal.getTitle() : "A new offer")
                + amount
                + " is now live. Open Hot Deals from your menu to view it.";
        userRepository.findAll().stream()
                .filter(user -> user.isActive() && "CUSTOMER".equalsIgnoreCase(user.getRole()))
                .forEach(customer -> notificationService.create(
                        customer.getId(),
                        "🔥 New Hot Deal",
                        message,
                        "promo"
                ));
    }

    private boolean parseBooleanField(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim());
    }

    private Map<String, Object> toHotDealView(HotDeal deal) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", deal.getId());
        row.put("title", deal.getTitle());
        row.put("description", deal.getDescription());
        row.put("badge", deal.getBadge() != null ? deal.getBadge() : "Hot Deal");
        row.put("price", deal.getPrice());
        row.put("originalPrice", deal.getOriginalPrice());
        row.put("discountPercent", deal.getDiscountPercent());
        row.put("imageUrl", deal.getImageUrl());
        row.put("productId", deal.getProductId());
        row.put("ctaLabel", deal.getCtaLabel() != null ? deal.getCtaLabel() : "Shop now");
        row.put("ctaLink", deal.getCtaLink() != null ? deal.getCtaLink() : "/customer/products");
        row.put("active", deal.isActive());
        row.put("scheduleStatus", hotDealScheduleStatus(deal, LocalDateTime.now()));
        row.put("startsAt", deal.getStartsAt());
        row.put("expiresAt", deal.getExpiresAt());
        row.put("priceApplied", deal.isPriceApplied());
        row.put("createdByName", deal.getCreatedByName());
        row.put("createdAt", deal.getCreatedAt());
        return row;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseRoles(Object raw) {
        if (raw == null) {
            return List.of("ALL");
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).map(String::toUpperCase).collect(Collectors.toList());
        }
        return List.of(raw.toString().toUpperCase());
    }

    private long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private int parseInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }
}
