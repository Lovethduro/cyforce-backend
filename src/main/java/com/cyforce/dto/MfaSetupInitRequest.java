package com.cyforce.dto;

import lombok.Data;

@Data
public class MfaSetupInitRequest {
    private String userId;
    private String method;
    /** When true, start a new setup while MFA stays active until verification succeeds. */
    private boolean reconfigure;
}
