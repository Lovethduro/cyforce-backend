package com.cyforce.service;

import com.cyforce.model.Product;
import com.cyforce.model.User;
import com.cyforce.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final RequestUserService requestUserService;
    private final FileStorageService fileStorageService;
    private final AuditLogService auditLogService;

    public ProductService(ProductRepository productRepository,
                          RequestUserService requestUserService,
                          FileStorageService fileStorageService,
                          AuditLogService auditLogService) {
        this.productRepository = productRepository;
        this.requestUserService = requestUserService;
        this.fileStorageService = fileStorageService;
        this.auditLogService = auditLogService;
    }

    public List<Product> listPublic() {
        return productRepository.findByActiveTrueOrderByCreatedAtDesc();
    }

    public List<Product> listAll(String adminId) {
        requireAdmin(adminId);
        return productRepository.findAllByOrderByCreatedAtDesc();
    }

    public Product create(String adminId, Map<String, String> fields, MultipartFile image) {
        User admin = requireAdmin(adminId);
        Product product = new Product();
        applyFields(product, fields);
        product.setImageUrl(fileStorageService.storeProductImage(image));
        product.setCreatedBy(admin.getId());
        product.setCreatedAt(LocalDateTime.now());
        product.setUpdatedAt(LocalDateTime.now());
        Product saved = productRepository.save(product);
        auditLogService.log(admin, "PRODUCT_CREATE", "Products", saved.getName());
        return saved;
    }

    public Product update(String adminId, String productId, Map<String, String> fields, MultipartFile image) {
        User admin = requireAdmin(adminId);
        Product product = requireProduct(productId);
        applyFields(product, fields);
        if (image != null && !image.isEmpty()) {
            fileStorageService.deleteIfStored(product.getImageUrl());
            product.setImageUrl(fileStorageService.storeProductImage(image));
        }
        product.setUpdatedAt(LocalDateTime.now());
        Product saved = productRepository.save(product);
        auditLogService.log(admin, "PRODUCT_UPDATE", "Products", saved.getName());
        return saved;
    }

    public void delete(String adminId, String productId) {
        User admin = requireAdmin(adminId);
        Product product = requireProduct(productId);
        product.setActive(false);
        product.setUpdatedAt(LocalDateTime.now());
        productRepository.save(product);
        auditLogService.log(admin, "PRODUCT_DEACTIVATE", "Products", product.getName());
    }

    private User requireAdmin(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "ADMIN");
        return user;
    }

    private Product requireProduct(String id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found"));
    }

    private void applyFields(Product product, Map<String, String> fields) {
        if (fields.get("name") != null && !fields.get("name").isBlank()) {
            product.setName(fields.get("name").trim());
        }
        if (fields.get("category") != null && !fields.get("category").isBlank()) {
            product.setCategory(fields.get("category").trim());
        }
        if (fields.get("price") != null && !fields.get("price").isBlank()) {
            product.setPrice(Long.parseLong(fields.get("price").trim()));
        }
        if (fields.get("originalPrice") != null && !fields.get("originalPrice").isBlank()) {
            product.setOriginalPrice(Long.parseLong(fields.get("originalPrice").trim()));
        } else if (fields.containsKey("originalPrice")) {
            product.setOriginalPrice(null);
        }
        if (fields.get("description") != null) {
            product.setDescription(fields.get("description").trim());
        }
        if (fields.get("inStock") != null) {
            product.setInStock(Boolean.parseBoolean(fields.get("inStock")));
        }
        if (fields.get("featured") != null) {
            product.setFeatured(Boolean.parseBoolean(fields.get("featured")));
        }
        if (fields.get("active") != null) {
            product.setActive(Boolean.parseBoolean(fields.get("active")));
        }
    }
}
