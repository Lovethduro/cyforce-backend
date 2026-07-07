package com.cyforce.service;

import com.cyforce.model.Product;
import com.cyforce.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class QuoteBundleService {

    private final ProductRepository productRepository;
    private final GroqAiService groqAiService;

    public QuoteBundleService(ProductRepository productRepository, GroqAiService groqAiService) {
        this.productRepository = productRepository;
        this.groqAiService = groqAiService;
    }

    public Map<String, Object> suggestBundle(Map<String, Object> body) {
        String quoteType = stringVal(body.get("quoteType")).toLowerCase(Locale.ROOT);
        String productType = stringVal(body.get("productType"));
        String productId = stringVal(body.get("productId"));
        String existingDetails = stringVal(body.get("existingProductDetails"));
        String installationAddress = stringVal(body.get("installationAddress"));
        String deliveryAddress = stringVal(body.get("deliveryAddress"));
        int quantity = parseQuantity(body.get("quantity"));

        List<Product> catalog = productRepository.findAll().stream()
                .filter(Product::isActive)
                .filter(p -> p.isInStock() || p.getStockQuantity() > 0)
                .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        String context = String.join(" ",
                quoteType, productType, existingDetails, installationAddress, deliveryAddress).toLowerCase(Locale.ROOT);

        String bundleCategory = detectCategory(context, productId, catalog);
        List<Map<String, Object>> items = buildBundleItems(bundleCategory, quoteType, quantity, productId, catalog);
        long totalKobo = items.stream()
                .mapToLong(item -> ((Number) item.getOrDefault("lineTotalKobo", 0L)).longValue())
                .sum();

        String installNote = installNoteFor(quoteType, bundleCategory);
        String summary = buildSummary(bundleCategory, quoteType, items, installNote, groqAiService.isConfigured());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("category", bundleCategory);
        response.put("items", items);
        response.put("estimatedTotalKobo", totalKobo);
        response.put("estimatedTotal", formatNaira(totalKobo));
        response.put("installNote", installNote);
        response.put("summary", summary);
        response.put("aiEnabled", groqAiService.isConfigured());
        return response;
    }

    private List<Map<String, Object>> buildBundleItems(String category,
                                                       String quoteType,
                                                       int quantity,
                                                       String selectedProductId,
                                                       List<Product> catalog) {
        List<Map<String, Object>> items = new ArrayList<>();
        int baseQty = Math.max(1, quantity);

        if (!selectedProductId.isBlank()) {
            catalog.stream().filter(p -> selectedProductId.equals(p.getId())).findFirst()
                    .ifPresent(product -> items.add(toLineItem(product, baseQty, "Selected product")));
        }

        if (items.isEmpty()) {
            List<Product> primary = pickProducts(catalog, categoryKeywords(category), 1);
            for (Product product : primary) {
                items.add(toLineItem(product, baseQty, "Primary equipment"));
            }
        }

        if (!"products_only".equals(quoteType)) {
            List<Product> accessories = pickProducts(catalog, accessoryKeywords(category), 2).stream()
                    .filter(p -> items.stream().noneMatch(i -> p.getId().equals(i.get("productId"))))
                    .limit(2)
                    .toList();
            for (Product product : accessories) {
                items.add(toLineItem(product, 1, "Recommended accessory"));
            }
        }

        if (items.isEmpty() && !catalog.isEmpty()) {
            items.add(toLineItem(catalog.get(0), baseQty, "Catalog suggestion"));
        }
        return items;
    }

    private List<Product> pickProducts(List<Product> catalog, List<String> keywords, int limit) {
        return catalog.stream()
                .sorted(Comparator.comparingInt((Product p) -> scoreProduct(p, keywords)).reversed()
                        .thenComparing(Product::getName, String.CASE_INSENSITIVE_ORDER))
                .filter(p -> scoreProduct(p, keywords) > 0)
                .limit(limit)
                .toList();
    }

    private int scoreProduct(Product product, List<String> keywords) {
        String text = ((product.getName() == null ? "" : product.getName()) + " "
                + (product.getCategory() == null ? "" : product.getCategory()) + " "
                + (product.getDescription() == null ? "" : product.getDescription())).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                score += keyword.length() > 4 ? 3 : 2;
            }
        }
        return score;
    }

    private String detectCategory(String context, String productId, List<Product> catalog) {
        if (!productId.isBlank()) {
            Optional<Product> selected = catalog.stream().filter(p -> productId.equals(p.getId())).findFirst();
            if (selected.isPresent()) {
                String category = selected.get().getCategory() == null ? "" : selected.get().getCategory().toLowerCase(Locale.ROOT);
                if (category.contains("cctv") || category.contains("camera") || category.contains("security")) return "cctv";
                if (category.contains("solar")) return "solar";
                if (category.contains("access") || category.contains("alarm")) return "access_control";
            }
        }
        if (containsAny(context, "cctv", "camera", "nvr", "dvr", "surveillance")) return "cctv";
        if (containsAny(context, "solar", "inverter", "battery", "panel")) return "solar";
        if (containsAny(context, "access", "alarm", "biometric", "door")) return "access_control";
        if (containsAny(context, "network", "switch", "router", "ict")) return "ict";
        return "general";
    }

    private List<String> categoryKeywords(String category) {
        return switch (category) {
            case "cctv" -> List.of("cctv", "camera", "nvr", "dvr", "surveillance");
            case "solar" -> List.of("solar", "inverter", "battery", "panel");
            case "access_control" -> List.of("access", "alarm", "biometric", "door");
            case "ict" -> List.of("switch", "router", "network", "server");
            default -> List.of("security", "automation", "enterprise");
        };
    }

    private List<String> accessoryKeywords(String category) {
        return switch (category) {
            case "cctv" -> List.of("cable", "storage", "mount", "ups", "nvr");
            case "solar" -> List.of("battery", "cable", "mount", "controller");
            case "access_control" -> List.of("reader", "lock", "cable", "panel");
            default -> List.of("cable", "install", "accessory");
        };
    }

    private Map<String, Object> toLineItem(Product product, int quantity, String reason) {
        long lineTotal = product.getPrice() * quantity;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", product.getId());
        item.put("name", product.getName());
        item.put("category", product.getCategory());
        item.put("quantity", quantity);
        item.put("unitPriceKobo", product.getPrice());
        item.put("unitPrice", formatNaira(product.getPrice()));
        item.put("lineTotalKobo", lineTotal);
        item.put("lineTotal", formatNaira(lineTotal));
        item.put("reason", reason);
        return item;
    }

    private String installNoteFor(String quoteType, String category) {
        if ("products_only".equals(quoteType)) {
            return "Delivery bundle only — installation can be quoted separately if needed.";
        }
        return switch (category) {
            case "cctv" -> "Includes onsite survey, camera mounting, NVR setup, and remote viewing configuration.";
            case "solar" -> "Includes load assessment, inverter/battery placement, and commissioning.";
            case "access_control" -> "Includes reader installation, controller programming, and user onboarding.";
            default -> "Includes standard installation, testing, and handover.";
        };
    }

    private String buildSummary(String category,
                                String quoteType,
                                List<Map<String, Object>> items,
                                String installNote,
                                boolean aiEnabled) {
        String itemList = items.stream()
                .map(i -> i.get("quantity") + "× " + i.get("name"))
                .collect(Collectors.joining(", "));
        String baseline = "Suggested " + category.replace('_', ' ') + " bundle for "
                + quoteType.replace('_', ' ') + ": " + itemList + ". " + installNote;

        if (!aiEnabled) {
            return baseline;
        }

        try {
            return groqAiService.complete(
                    "You summarize CyForce product bundles for customers in Nigeria. "
                            + "Use plain sentences only — no markdown, no bullet lists, no headings. "
                            + "Only mention the exact products and quantities provided. "
                            + "Do not invent cameras, installers, or items not in the list. Max 55 words.",
                    "Write a short friendly summary using only these facts:\n" + baseline
            );
        } catch (RuntimeException e) {
            return baseline;
        }
    }

    private String formatNaira(long kobo) {
        if (kobo <= 0) {
            return "₦0";
        }
        return "₦" + String.format("%,d", kobo / 100);
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String stringVal(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private int parseQuantity(Object value) {
        if (value == null) {
            return 1;
        }
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        try {
            return Math.max(1, Integer.parseInt(value.toString().trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
