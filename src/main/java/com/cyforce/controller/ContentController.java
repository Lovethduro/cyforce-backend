package com.cyforce.controller;

import com.cyforce.service.ContentService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/content")
@CrossOrigin(origins = "http://localhost:3000")
public class ContentController {

    private final ContentService contentService;

    public ContentController(ContentService contentService) {
        this.contentService = contentService;
    }

    @GetMapping("/hot-deals")
    public ResponseEntity<?> hotDeals() {
        return ResponseEntity.ok(contentService.activeHotDeals());
    }

    @GetMapping("/hot-deals/all")
    public ResponseEntity<?> allHotDeals(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(contentService.allHotDeals(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/hot-deals", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> createHotDeal(@RequestHeader("X-User-Id") String userId,
                                           @RequestParam String title,
                                           @RequestParam(required = false) String description,
                                           @RequestParam(required = false) String badge,
                                           @RequestParam(required = false) String price,
                                           @RequestParam(required = false) String originalPrice,
                                           @RequestParam(required = false) String discountPercent,
                                           @RequestParam String productId,
                                           @RequestParam(required = false) String ctaLabel,
                                           @RequestParam(required = false) String ctaLink,
                                           @RequestParam(required = false) String startsAt,
                                           @RequestParam(required = false) String expiresAt,
                                           @RequestParam(required = false, defaultValue = "true") String active,
                                           @RequestParam(required = false) MultipartFile image) {
        try {
            return ResponseEntity.ok(contentService.createHotDeal(userId, hotDealFields(
                    title, description, badge, price, originalPrice, discountPercent, productId, ctaLabel, ctaLink, startsAt, expiresAt, active
            ), image));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping(value = "/hot-deals/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> updateHotDeal(@RequestHeader("X-User-Id") String userId,
                                           @PathVariable String id,
                                           @RequestParam(required = false) String title,
                                           @RequestParam(required = false) String description,
                                           @RequestParam(required = false) String badge,
                                           @RequestParam(required = false) String price,
                                           @RequestParam(required = false) String originalPrice,
                                           @RequestParam(required = false) String discountPercent,
                                           @RequestParam(required = false) String productId,
                                           @RequestParam(required = false) String ctaLabel,
                                           @RequestParam(required = false) String ctaLink,
                                           @RequestParam(required = false) String startsAt,
                                           @RequestParam(required = false) String expiresAt,
                                           @RequestParam(required = false) String active,
                                           @RequestParam(required = false) MultipartFile image) {
        try {
            return ResponseEntity.ok(contentService.updateHotDeal(userId, id, hotDealFields(
                    title, description, badge, price, originalPrice, discountPercent, productId, ctaLabel, ctaLink, startsAt, expiresAt, active
            ), image));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/hot-deals/{id}")
    public ResponseEntity<?> deleteHotDeal(@RequestHeader("X-User-Id") String userId,
                                           @PathVariable String id) {
        try {
            contentService.deleteHotDeal(userId, id);
            return ResponseEntity.ok(Map.of("message", "Hot deal removed"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/motivational")
    public ResponseEntity<?> motivational(@RequestParam(required = false) String role,
                                          @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ResponseEntity.ok(contentService.motivationalForUser(userId, role));
    }

    @GetMapping("/motivational/all")
    public ResponseEntity<?> allMotivational(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(contentService.listMotivationalMessages(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/motivational")
    public ResponseEntity<?> createMotivational(@RequestHeader("X-User-Id") String userId,
                                                @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(contentService.createMotivationalMessage(userId, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, String> hotDealFields(String title, String description, String badge,
                                              String price, String originalPrice, String discountPercent,
                                              String productId, String ctaLabel, String ctaLink,
                                              String startsAt, String expiresAt, String active) {
        Map<String, String> fields = new HashMap<>();
        if (title != null) fields.put("title", title);
        if (description != null) fields.put("description", description);
        if (badge != null) fields.put("badge", badge);
        if (price != null) fields.put("price", price);
        if (originalPrice != null) fields.put("originalPrice", originalPrice);
        if (discountPercent != null) fields.put("discountPercent", discountPercent);
        if (productId != null) fields.put("productId", productId);
        if (ctaLabel != null) fields.put("ctaLabel", ctaLabel);
        if (ctaLink != null) fields.put("ctaLink", ctaLink);
        if (startsAt != null) fields.put("startsAt", startsAt);
        if (expiresAt != null) fields.put("expiresAt", expiresAt);
        if (active != null) fields.put("active", active);
        return fields;
    }
}
