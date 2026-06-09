package com.chatassistant.aichatassistant.bench;

import com.chatassistant.aichatassistant.client.OllamaChatRequest;
import com.chatassistant.aichatassistant.client.OllamaChatRequest.OllamaChatMessage;
import com.chatassistant.aichatassistant.client.OllamaChatResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Minimal Ollama HTTP client used by the benchmark.
 *
 * Kept separate from the production {@code OllamaService} so that benchmark behavior
 * (timeouts, no retries, deterministic options) doesn't have to mirror production
 * decisions. The two share only the request/response DTOs.
 */
public final class OllamaBenchClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public OllamaBenchClient(String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public OllamaChatResponse chat(String model, String systemPrompt, String userMessage, String format) throws Exception {
        OllamaChatRequest req = new OllamaChatRequest(
                model,
                List.of(
                        new OllamaChatMessage("system", systemPrompt),
                        new OllamaChatMessage("user", userMessage)
                ),
                false,
                format,
                Map.of("temperature", 0.0, "num_ctx", 4096)
        );

        HttpRequest http = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req)))
                .build();

        HttpResponse<String> resp = this.http.send(http, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Ollama HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return mapper.readValue(resp.body(), OllamaChatResponse.class);
    }

    /** Returns size-on-disk (bytes) of {@code model} if loaded, else 0. */
    public long loadedModelSizeBytes(String model) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ps"))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return 0L;
            JsonNode node = mapper.readTree(resp.body()).path("models");
            for (JsonNode m : node) {
                if (model.equals(m.path("name").asText()) || model.equals(m.path("model").asText())) {
                    return m.path("size").asLong(0L);
                }
            }
        } catch (Exception ignored) {
            // best-effort metric — don't crash the benchmark over it
        }
        return 0L;
    }
}
