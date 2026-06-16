package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "leads")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Lead {
    @Id
    private String id;
    private String name;
    private String email;
    private String phone;
    private String company;
    private String source;
    private String status;
    private int score;
    private String ownerId;
    private String ownerName;
    private String conversationId;
    private String quoteType;
    private String details;
    private String productId;
    private String productName;
    private Integer quantity;
    private String deliveryAddress;
    private String installationAddress;
    private String preferredInstallationDate;
    private String siteContactName;
    private String siteContactPhone;
    private String productType;
    private String existingProductDetails;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
