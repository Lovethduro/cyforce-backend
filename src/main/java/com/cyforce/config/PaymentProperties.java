package com.cyforce.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "payment")
public class PaymentProperties {
    private String callbackBaseUrl = "http://localhost:3000";
    private Paystack paystack = new Paystack();
    private Flutterwave flutterwave = new Flutterwave();

    @Data
    public static class Paystack {
        private String secretKey = "";
        private String publicKey = "";
        private String baseUrl = "https://api.paystack.co";
    }

    @Data
    public static class Flutterwave {
        private String secretKey = "";
        private String publicKey = "";
        private String baseUrl = "https://api.flutterwave.com/v3";
    }
}
