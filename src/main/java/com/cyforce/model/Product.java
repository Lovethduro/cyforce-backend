package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "products")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Product {
    @Id
    private String id;
    private String name;
    private String category;
    private long price;
    private Long originalPrice;
    private String description;
    private String imageUrl;
    private boolean inStock = true;
    private boolean featured = false;
    private boolean active = true;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
