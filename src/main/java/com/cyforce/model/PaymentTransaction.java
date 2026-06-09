package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

@Document(collection = "payment_transactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentTransaction {
    @Id
    private String id;
    @Indexed(unique = true)
    private String reference;
    private String provider;
    private String providerReference;
    private String userId;
    private String userEmail;
    private long amount;
    private String currency;
    private String status;
    private String description;
    private String authorizationUrl;
    private Map<String, Object> metadata;
    private LocalDateTime createdAt;
    private LocalDateTime verifiedAt;
}
