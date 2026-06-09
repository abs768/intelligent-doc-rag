package com.chatassistant.aichatassistant.service;

import com.chatassistant.aichatassistant.client.OllamaChatRequest;
import com.chatassistant.aichatassistant.client.OllamaChatRequest.OllamaChatMessage;
import com.chatassistant.aichatassistant.client.OllamaChatResponse;
import com.chatassistant.aichatassistant.exception.ServiceUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Calls Ollama's /api/chat endpoint with proper role-based messages.
 *
 * Three public entry points:
 *   - chat(...)           — text-in/text-out, default model (existing callers)
 *   - chatRaw(...)        — returns the full response (including timing fields) and lets
 *                           the caller pick the model. Used by the benchmark.
 *   - chatStructured(...) — forces format:"json", parses into a target type, validates
 *                           with Bean Validation, retries with the violations fed back in.
 */
@Service
public class OllamaService {

    private static final Logger logger = LoggerFactory.getLogger(OllamaService.class);
    private static final int MAX_HTTP_RETRIES = 3;
    private static final int MAX_STRUCTURED_RETRIES = 2;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final String baseUrl;
    private final String defaultModel;

    public OllamaService(
            @Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${ollama.model:llama2}") String defaultModel,
            ObjectMapper objectMapper,
            Validator validator
    ) {
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ---------------- public API ----------------

    public String chat(String systemPrompt, String userMessage) {
        return callOllama(defaultModel, systemPrompt, userMessage, null).message().content();
    }

    public OllamaChatResponse chatRaw(String model, String systemPrompt, String userMessage, String format) {
        return callOllama(model, systemPrompt, userMessage, format);
    }

    public <T> T chatStructured(String systemPrompt, String userMessage, Class<T> type) {
        String currentUserMessage = userMessage;
        Exception lastError = null;

        for (int attempt = 0; attempt <= MAX_STRUCTURED_RETRIES; attempt++) {
            String json = callOllama(defaultModel, systemPrompt, currentUserMessage, "json")
                    .message()
                    .content();

            try {
                T parsed = objectMapper.readValue(json, type);
                Set<ConstraintViolation<T>> violations = validator.validate(parsed);
                if (violations.isEmpty()) {
                    return parsed;
                }
                String errors = formatViolations(violations);
                logger.warn("Structured output validation failed (attempt {}): {}", attempt + 1, errors);
                currentUserMessage = userMessage
                        + "\n\nYour previous response failed validation:\n" + errors
                        + "\nReturn ONLY valid JSON that satisfies the schema.";
                lastError = new IllegalArgumentException("Validation failed: " + errors);
            } catch (Exception parseError) {
                logger.warn("Structured output parse failed (attempt {}): {}", attempt + 1, parseError.getMessage());
                currentUserMessage = userMessage
                        + "\n\nYour previous response was not valid JSON: " + parseError.getMessage()
                        + "\nReturn ONLY valid JSON that satisfies the schema.";
                lastError = parseError;
            }
        }

        throw new ServiceUnavailableException(
                "Structured output failed after " + (MAX_STRUCTURED_RETRIES + 1) + " attempts", lastError);
    }

    // ---------------- internals ----------------

    private OllamaChatResponse callOllama(String model, String systemPrompt, String userMessage, String format) {
        List<OllamaChatMessage> messages = List.of(
                new OllamaChatMessage("system", systemPrompt),
                new OllamaChatMessage("user", userMessage)
        );

        OllamaChatRequest requestDto = new OllamaChatRequest(
                model,
                messages,
                false,
                format,
                Map.of(
                        "temperature", 0.0,
                        "num_ctx", 4096
                )
        );

        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_HTTP_RETRIES; attempt++) {
            try {
                String body = objectMapper.writeValueAsString(requestDto);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/chat"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofMinutes(2))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    throw new RuntimeException(
                            "Ollama error: HTTP " + response.statusCode() + " → " + response.body());
                }

                OllamaChatResponse result = objectMapper.readValue(response.body(), OllamaChatResponse.class);
                logger.debug("Ollama chat succeeded (model={}, attempt={})", model, attempt);
                return result;

            } catch (Exception e) {
                lastException = e;
                logger.warn("Ollama request failed (model={}, attempt {}/{}): {}",
                        model, attempt, MAX_HTTP_RETRIES, e.getMessage());
            }
        }

        throw new ServiceUnavailableException("Ollama service unavailable after retries", lastException);
    }

    private <T> String formatViolations(Set<ConstraintViolation<T>> violations) {
        return violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
    }
}
