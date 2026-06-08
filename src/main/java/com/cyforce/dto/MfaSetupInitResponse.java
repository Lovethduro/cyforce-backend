package com.cyforce.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MfaSetupInitResponse {
    private String method;
    private String secret;
    private String otpAuthUrl;
    private String message;
}
