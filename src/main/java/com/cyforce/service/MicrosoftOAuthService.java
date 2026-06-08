package com.cyforce.service;

import com.cyforce.dto.OAuthUserInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Service
public class MicrosoftOAuthService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public OAuthUserInfo verifyAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new RuntimeException("Microsoft access token is required");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://graph.microsoft.com/v1.0/me"))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Invalid Microsoft token");
            }

            JsonNode profile = objectMapper.readTree(response.body());
            String email = firstNonBlank(
                    text(profile, "mail"),
                    text(profile, "userPrincipalName")
            );
            String providerId = text(profile, "id");
            String fullName = firstNonBlank(
                    text(profile, "displayName"),
                    email
            );

            if (email == null || email.isBlank() || providerId == null || providerId.isBlank()) {
                throw new RuntimeException("Microsoft account profile is incomplete");
            }

            return new OAuthUserInfo("MICROSOFT", providerId, email, fullName);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to verify Microsoft token: " + e.getMessage(), e);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
