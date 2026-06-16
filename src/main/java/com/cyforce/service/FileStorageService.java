package com.cyforce.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;

@Service
public class FileStorageService {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif"
    );
    private static final long MAX_BYTES = 5 * 1024 * 1024;

    public String storeProductImage(MultipartFile file) {
        return storeImage(file, "products", true);
    }

    public String storeHotDealImage(MultipartFile file) {
        return storeImage(file, "hot-deals", true);
    }

    public String storeAvatar(MultipartFile file) {
        return storeImage(file, "avatars", false);
    }

    public String storeLogo(MultipartFile file) {
        return storeImage(file, "logos", false);
    }

    public String storeTicketAttachment(MultipartFile file) {
        return storeImage(file, "tickets", false);
    }

    private String storeImage(MultipartFile file, String folder, boolean required) {
        if (file == null || file.isEmpty()) {
            if (required) throw new RuntimeException("Image is required");
            return null;
        }
        if (file.getSize() > MAX_BYTES) {
            throw new RuntimeException("Image must be 5MB or smaller");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw new RuntimeException("Only JPG, PNG, WEBP, or GIF images are allowed");
        }

        try {
            Path dir = Paths.get("uploads", folder);
            Files.createDirectories(dir);
            String extension = extensionFor(contentType);
            String filename = UUID.randomUUID() + extension;
            Path target = dir.resolve(filename);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            return "/uploads/" + folder + "/" + filename;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store image: " + e.getMessage());
        }
    }

    public void deleteIfStored(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith("/uploads/")) {
            return;
        }
        try {
            Path path = Paths.get(imageUrl.substring(1));
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup
        }
    }

    private String extensionFor(String contentType) {
        return switch (contentType.toLowerCase()) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }
}
