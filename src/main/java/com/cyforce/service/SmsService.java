package com.cyforce.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    private final String provider;
    private final boolean devExposeCode;
    private final boolean termiiFallbackToDev;
    private final String termiiApiKey;
    private final String termiiBaseUrl;
    private final String termiiSenderId;
    private final String termiiChannel;
    private final String twilioAccountSid;
    private final String twilioAuthToken;
    private final String twilioFromNumber;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SmsService(@Value("${sms.provider:dev}") String provider,
                      @Value("${sms.dev.expose-code:true}") boolean devExposeCode,
                      @Value("${termii.fallback-to-dev:true}") boolean termiiFallbackToDev,
                      @Value("${termii.api-key:}") String termiiApiKey,
                      @Value("${termii.base-url:https://api.ng.termii.com}") String termiiBaseUrl,
                      @Value("${termii.sender-id:cyforcetech}") String termiiSenderId,
                      @Value("${termii.channel:generic}") String termiiChannel,
                      @Value("${twilio.account-sid:}") String twilioAccountSid,
                      @Value("${twilio.auth-token:}") String twilioAuthToken,
                      @Value("${twilio.from-number:}") String twilioFromNumber) {
        this.provider = provider == null || provider.isBlank() ? "dev" : provider.toLowerCase();
        this.devExposeCode = devExposeCode;
        this.termiiFallbackToDev = termiiFallbackToDev;
        this.termiiApiKey = termiiApiKey;
        this.termiiBaseUrl = normalizeBaseUrl(termiiBaseUrl);
        this.termiiSenderId = termiiSenderId;
        this.termiiChannel = termiiChannel == null || termiiChannel.isBlank() ? "generic" : termiiChannel;
        this.twilioAccountSid = twilioAccountSid;
        this.twilioAuthToken = twilioAuthToken;
        this.twilioFromNumber = twilioFromNumber;
    }

    public String sendMfaCode(String toPhone, String code) {
        if (toPhone == null || toPhone.isBlank()) {
            throw new RuntimeException("No phone number on your account. Please register with a valid phone number.");
        }

        String message = "Your CyForce MFA code is: " + code + ". It expires in 10 minutes.";

        return switch (provider) {
            case "dev" -> sendDevMode(toPhone, code, message);
            case "termii" -> sendViaTermii(toPhone, code, message);
            case "twilio" -> sendViaTwilio(toPhone, message);
            default -> throw new RuntimeException("Unknown SMS provider: " + provider);
        };
    }

    public boolean isDevMode() {
        return "dev".equals(provider);
    }

    private String sendDevMode(String toPhone, String code, String message) {
        return sendDevMode(toPhone, code, message, "Development mode — your code is shown below and in the server console:");
    }

    private String sendDevMode(String toPhone, String code, String message, String prefix) {
        log.warn("=================================================");
        log.warn("DEV SMS to {}: {}", toPhone, message);
        log.warn("MFA CODE: {}", code);
        log.warn("=================================================");

        if (devExposeCode) {
            return prefix + " " + code;
        }
        return prefix + " Check the backend server console for your SMS code.";
    }

    private String sendViaTermii(String toPhone, String code, String message) {
        if (termiiApiKey == null || termiiApiKey.isBlank()) {
            throw new RuntimeException("Termii is not configured. Add termii.api-key to application.properties");
        }

        try {
            String normalizedPhone = normalizePhoneForTermii(toPhone);
            log.info("Sending Termii SMS to {} via sender {} on channel {}", normalizedPhone, termiiSenderId, termiiChannel);

            Map<String, Object> payload = Map.of(
                    "api_key", termiiApiKey,
                    "to", normalizedPhone,
                    "from", termiiSenderId,
                    "sms", message,
                    "type", "plain",
                    "channel", termiiChannel
            );

            String responseBody = postTermii("/api/sms/send", payload);
            JsonNode body = objectMapper.readTree(responseBody);
            String status = body.path("code").asText("");
            if (!"ok".equalsIgnoreCase(status)) {
                throw new RuntimeException("Termii error: " + extractTermiiError(responseBody));
            }

            return null;
        } catch (RuntimeException e) {
            if (termiiFallbackToDev && isSenderIdError(e.getMessage())) {
                log.warn("Termii sender ID '{}' is not approved yet. Falling back to dev mode.", termiiSenderId);
                return sendDevMode(toPhone, code, message,
                        "Termii sender ID is still pending approval — use this code to continue MFA setup:");
            }
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to send SMS via Termii: " + e.getMessage(), e);
        }
    }

    private String postTermii(String path, Map<String, Object> payload) throws Exception {
        String json = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(termiiBaseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        String responseBody = response.body() == null ? "" : response.body();
        log.info("Termii response ({}): {}", response.statusCode(), responseBody);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Termii error: " + extractTermiiError(responseBody));
        }

        return responseBody;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.ng.termii.com";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private boolean isSenderIdError(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("ApplicationSenderId not found")
                || message.contains("Sender Id")
                || message.contains("Invalid Sender Id");
    }

    private String normalizePhoneForTermii(String phone) {
        String digits = phone.replaceAll("[^0-9]", "");

        // +23408012345678 -> 2348012345678
        if (digits.startsWith("2340")) {
            digits = "234" + digits.substring(4);
        }
        // 08012345678 -> 2348012345678
        if (digits.startsWith("0") && digits.length() >= 10) {
            digits = "234" + digits.substring(1);
        }

        if (!digits.startsWith("234") || digits.length() < 12) {
            throw new RuntimeException(
                    "Phone must be a valid Nigerian number in format 234XXXXXXXXXX. You entered: " + phone
            );
        }

        return digits;
    }

    private String extractTermiiError(String responseBody) {
        try {
            JsonNode body = objectMapper.readTree(responseBody);
            String message = body.path("message").asText("");
            if (!message.isBlank()) {
                if (message.contains("ApplicationSenderId not found") || message.contains("Sender Id")) {
                    return message + ". Your sender ID is still pending on Termii — wait for approval at https://accounts.termii.com or contact Termii support.";
                }
                return message;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return responseBody.isBlank() ? "Unknown Termii error" : responseBody;
    }

    private String sendViaTwilio(String toPhone, String message) {
        if (twilioAccountSid == null || twilioAccountSid.isBlank()
                || twilioAuthToken == null || twilioAuthToken.isBlank()
                || twilioFromNumber == null || twilioFromNumber.isBlank()) {
            throw new RuntimeException("Twilio is not configured. Add Twilio credentials to application.properties");
        }

        try {
            String form = "To=" + encode(toPhone)
                    + "&From=" + encode(twilioFromNumber)
                    + "&Body=" + encode(message);

            String credentials = Base64.getEncoder()
                    .encodeToString((twilioAccountSid + ":" + twilioAuthToken).getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.twilio.com/2010-04-01/Accounts/" + twilioAccountSid + "/Messages.json"))
                    .header("Authorization", "Basic " + credentials)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("Failed to send SMS via Twilio. Check your settings and phone number.");
            }

            return null;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to send SMS via Twilio: " + e.getMessage(), e);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
