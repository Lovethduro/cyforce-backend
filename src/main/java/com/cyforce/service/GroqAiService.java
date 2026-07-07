package com.cyforce.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GroqAiService {

    private static final Logger log = LoggerFactory.getLogger(GroqAiService.class);
    private static final URI GROQ_URL = URI.create("https://api.groq.com/openai/v1/chat/completions");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Value("${cyforce.groq.api-key:}")
    private String apiKey;

    @Value("${cyforce.groq.model:llama-3.1-8b-instant}")
    private String model;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String complete(String systemPrompt, String userPrompt) {
        if (!isConfigured()) {
            throw new RuntimeException("AI is not configured. Set cyforce.groq.api-key in application properties.");
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("temperature", 0.4);
            payload.put("max_tokens", 700);
            payload.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(GROQ_URL)
                    .timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Groq API error {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("AI request failed");
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new RuntimeException("AI returned an empty response");
            }
            return content.asText().trim();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Groq completion failed: {}", e.getMessage());
            throw new RuntimeException("AI request failed: " + e.getMessage());
        }
    }
}
