package com.cyforce.controller;

import com.cyforce.service.ProductService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/products")
@CrossOrigin(origins = "http://localhost:3000")
public class AdminProductController {

    private final ProductService productService;

    public AdminProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(productService.listAll(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> create(@RequestHeader("X-User-Id") String userId,
                                    @RequestParam String name,
                                    @RequestParam String category,
                                    @RequestParam String price,
                                    @RequestParam(required = false) String originalPrice,
                                    @RequestParam String description,
                                    @RequestParam(defaultValue = "0") String stockQuantity,
                                    @RequestParam(defaultValue = "true") String inStock,
                                    @RequestParam(defaultValue = "false") String featured,
                                    @RequestPart("image") MultipartFile image) {
        try {
            Map<String, String> fields = baseFields(name, category, price, originalPrice, description, stockQuantity, inStock, featured, "true");
            return ResponseEntity.ok(productService.create(userId, fields, image));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> update(@RequestHeader("X-User-Id") String userId,
                                    @PathVariable String id,
                                    @RequestParam String name,
                                    @RequestParam String category,
                                    @RequestParam String price,
                                    @RequestParam(required = false) String originalPrice,
                                    @RequestParam String description,
                                    @RequestParam(defaultValue = "0") String stockQuantity,
                                    @RequestParam(defaultValue = "true") String inStock,
                                    @RequestParam(defaultValue = "false") String featured,
                                    @RequestParam(defaultValue = "true") String active,
                                    @RequestPart(value = "image", required = false) MultipartFile image) {
        try {
            Map<String, String> fields = baseFields(name, category, price, originalPrice, description, stockQuantity, inStock, featured, active);
            return ResponseEntity.ok(productService.update(userId, id, fields, image));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            productService.delete(userId, id);
            return ResponseEntity.ok(Map.of("message", "Product removed from catalog"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, String> baseFields(String name, String category, String price, String originalPrice,
                                           String description, String stockQuantity, String inStock,
                                           String featured, String active) {
        Map<String, String> fields = new HashMap<>();
        fields.put("name", name);
        fields.put("category", category);
        fields.put("price", price);
        fields.put("originalPrice", originalPrice);
        fields.put("description", description);
        fields.put("stockQuantity", stockQuantity);
        fields.put("inStock", inStock);
        fields.put("featured", featured);
        fields.put("active", active);
        return fields;
    }
}
