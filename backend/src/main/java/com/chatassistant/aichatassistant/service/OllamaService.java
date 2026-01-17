package com.chatassistant.aichatassistant.service;

import com.chatassistant.aichatassistant.client.OllamaGenerateRequest;
import com.chatassistant.aichatassistant.client.OllamaGenerateResponse;
import com.chatassistant.aichatassistant.exception.ServiceUnavailableException;
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
import java.util.Map;

@Service
public class OllamaService {

    private static final Logger logger = LoggerFactory.getLogger(OllamaService.class);
    private static final int MAX_RETRIES = 3;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String model;

    public OllamaService(
            @Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${ollama.model:llama2}") String model,
            ObjectMapper objectMapper
    ) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public OllamaGenerateResponse generate(String prompt) {
        OllamaGenerateRequest requestDto =
                new OllamaGenerateRequest(
                        model,
                        prompt,
                        false,
                        Map.of(
                                "temperature", 0.2,
                                "num_ctx", 4096
                        )
                );

        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String body = objectMapper.writeValueAsString(requestDto);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/generate"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofMinutes(2))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    throw new RuntimeException(
                            "Ollama error: HTTP " + response.statusCode() + " → " + response.body()
                    );
                }

                OllamaGenerateResponse result =
                        objectMapper.readValue(response.body(), OllamaGenerateResponse.class);

                logger.debug("Ollama generation succeeded (attempt {})", attempt);
                return result;

            } catch (Exception e) {
                lastException = e;
                logger.warn("Ollama request failed (attempt {}/{}): {}",
                        attempt, MAX_RETRIES, e.getMessage());
            }
        }

        throw new ServiceUnavailableException(
                "Ollama service unavailable after retries",
                lastException
        );
    }
}
