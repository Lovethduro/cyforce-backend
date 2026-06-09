package com.cyforce.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {
    private String userId;
    private String fullName;
    private String email;
    private String phone;
    private String companyName;
    private String avatarUrl;
    private String logoUrl;
    private String profileImageUrl;
    private String preferredPaymentMethod;
    private String createdAt;
    private String customerType;
    private String role;
    private boolean emailVerified;
    private boolean mfaEnabled;
    private String mfaMethod;
    private boolean active;
}
