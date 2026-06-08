package com.cyforce.dto;

import lombok.Data;

@Data
public class RegisterRequest {
    private String fullName;
    private String email;
    private String phone;
    private String companyName;
    private String customerType;
    private String password;
}