package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "customer_referrals")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerReferral {
    @Id
    private String id;
    private String userId;
    private String referralCode;
    private int successfulReferrals;
    private String referredByCode;
    private String hearAboutUs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
