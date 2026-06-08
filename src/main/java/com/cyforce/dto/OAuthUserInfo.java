package com.cyforce.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OAuthUserInfo {
    private String provider;
    private String providerId;
    private String email;
    private String fullName;
}
