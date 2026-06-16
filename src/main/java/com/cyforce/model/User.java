package com.cyforce.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;

@Document(collection = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    private String id;

    private String fullName;

    @Indexed(unique = true)
    private String email;

    private String phone;

    private String password;

    private String authProvider = "LOCAL";

    private String providerId;

    private String companyName;

    private String avatarUrl;

    private String logoUrl;

    private String preferredPaymentMethod;

    private String customerType;

    private String role;

    private boolean isActive = true;

    private boolean isEmailVerified = false;

    private String verificationToken;

    private LocalDateTime verificationTokenExpiryDate;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime lastLoginAt;

    private LocalDateTime lastActivityAt;

    private LocalDateTime lastReengagementEmailAt;

    /** Null or true = show motivational banners; false = opted out */
    private Boolean showMotivationalMessages;

    private LocalDateTime emailVerifiedAt;

    private boolean mfaEnabled = false;

    private String mfaMethod;

    private String totpSecret;

    private String mfaPendingSecret;

    private String mfaPendingMethod;

    private String mfaPendingCode;

    private LocalDateTime mfaPendingCodeExpiry;

    private boolean mustChangePassword = false;

    private String passwordResetToken;

    private LocalDateTime passwordResetTokenExpiry;

    private String mfaLoginToken;

    private LocalDateTime mfaLoginTokenExpiry;

    private String mfaLoginCode;

    private LocalDateTime mfaLoginCodeExpiry;

    private double averageRating;

    private int ratingCount;

    public boolean wantsMotivationalMessages() {
        return showMotivationalMessages == null || showMotivationalMessages;
    }
}