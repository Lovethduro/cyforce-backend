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
}