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
    private String role;
    private boolean active;
    private boolean emailVerified;
    private boolean mfaEnabled;
    private String createdAt;
    private String lastLoginAt;
}
