package com.cyforce.dto;

import lombok.Data;

@Data
public class OAuthLoginRequest {
    private String token;
    private String role;
}
