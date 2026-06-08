package com.cyforce.dto;

import lombok.Data;

@Data
public class MfaSetupInitRequest {
    private String userId;
    private String method;
}
