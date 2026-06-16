package com.cyforce.dto;

import lombok.Data;

@Data
public class UpdateProfileRequest {
    private String fullName;
    private String phone;
    private String companyName;
    private String customerType;
    private String preferredPaymentMethod;
    private Boolean showMotivationalMessages;
}
