package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

@Document(collection = "hot_deals")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HotDeal {
    @Id
    private String id;
    private String title;
    private String description;
    private String badge;
    private Long price;
    private Long originalPrice;
    private Integer discountPercent;
    private String imageUrl;
    private String productId;
    private String ctaLabel;
    private String ctaLink;
    @Field("active")
    private boolean active = true;
    private LocalDateTime startsAt;
    private LocalDateTime expiresAt;
    /** Catalog list price captured before the promotional price is applied. */
    private Long catalogPriceBeforeDeal;
    private boolean priceApplied;
    private boolean promoNotified;
    private String createdById;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
