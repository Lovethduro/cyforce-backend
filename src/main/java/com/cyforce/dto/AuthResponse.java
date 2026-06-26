package com.cyforce.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private String userId;
    private String email;
    private String fullName;
    private String role;
    private boolean emailVerified;
    private boolean mfaEnabled;
    private String phone;
    private String avatarUrl;
    private String preferredPaymentMethod;
    private String createdAt;
    private boolean mustChangePassword;
    private boolean showMotivationalMessages = true;
    private boolean mfaRequired;
    private String mfaChallengeToken;
    private String mfaMethod;
    private boolean profileComplete = true;
    private String sessionId;
}