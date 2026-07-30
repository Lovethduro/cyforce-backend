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
    /** Count of referred customers who completed a purchase (conversion rewards). */
    private int successfulReferrals;
    private String referredByCode;
    private String hearAboutUs;
    /**
     * On a referred customer: when their purchase unlocked a reward for the referrer.
     * Prevents double-rewarding the same signup.
     */
    private LocalDateTime referrerRewardedAt;
    /** Percent off the referrer's next cart checkout (granted after a referred purchase). */
    private Integer pendingDiscountPercent;
    private String pendingDiscountReason;
    private LocalDateTime pendingDiscountGrantedAt;
    private LocalDateTime pendingDiscountConsumedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
