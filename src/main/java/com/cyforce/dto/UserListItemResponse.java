package com.cyforce.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserListItemResponse {
    private String id;
    private String fullName;
    private String email;
    private String phone;
    private String role;
    private String companyName;
    private String customerType;
    private boolean active;
    private boolean emailVerified;
    private boolean mfaEnabled;
    private String createdAt;
    private String lastLoginAt;
}
