package com.cyforce.service;

import com.cyforce.dto.OAuthUserInfo;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class GoogleOAuthService {

    private final String googleClientId;

    public GoogleOAuthService(@Value("${oauth.google.client-id:}") String googleClientId) {
        this.googleClientId = googleClientId;
    }

    public OAuthUserInfo verifyToken(String token) {
        if (googleClientId == null || googleClientId.isBlank()) {
            throw new RuntimeException("Google OAuth is not configured on the server");
        }

        try {
            return verifyIdToken(token);
        } catch (RuntimeException idTokenError) {
            return verifyAccessToken(token);
        }
    }

    private OAuthUserInfo verifyIdToken(String idTokenString) {
        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(),
                    GsonFactory.getDefaultInstance())
                    .setAudience(Collections.singletonList(googleClientId))
                    .build();

            GoogleIdToken idToken = verifier.verify(idTokenString);
            if (idToken == null) {
                throw new RuntimeException("Invalid Google token");
            }

            GoogleIdToken.Payload payload = idToken.getPayload();
            String email = payload.getEmail();
            if (email == null || email.isBlank()) {
                throw new RuntimeException("Google account does not provide an email address");
            }

            String fullName = (String) payload.get("name");
            if (fullName == null || fullName.isBlank()) {
                fullName = email;
            }

            return new OAuthUserInfo("GOOGLE", payload.getSubject(), email, fullName);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Invalid Google ID token", e);
        }
    }

    private OAuthUserInfo verifyAccessToken(String accessToken) {
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://www.googleapis.com/oauth2/v3/userinfo"))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Invalid Google token");
            }

            com.fasterxml.jackson.databind.JsonNode profile =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());

            String email = text(profile, "email");
            String providerId = text(profile, "sub");
            String fullName = firstNonBlank(text(profile, "name"), email);

            if (email == null || email.isBlank() || providerId == null || providerId.isBlank()) {
                throw new RuntimeException("Google account profile is incomplete");
            }

            return new OAuthUserInfo("GOOGLE", providerId, email, fullName);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to verify Google token: " + e.getMessage(), e);
        }
    }

    private String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode value = node.get(field);
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
