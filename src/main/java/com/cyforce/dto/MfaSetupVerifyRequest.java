package com.cyforce.dto;

import lombok.Data;

@Data
public class MfaSetupVerifyRequest {
    private String userId;
    private String code;
}
