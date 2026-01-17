package com.chatassistant.aichatassistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingService {

    @Value("${ollama.base-url}")
    private String baseUrl;

    @Value("${ollama.embedding-model}")
    private String embeddingModel;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    public List<Float> embed(String text) {
        try {
            String body = mapper.writeValueAsString(new EmbedRequest(embeddingModel, text));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/embeddings"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Embedding failed: " + response.statusCode());
            }

            JsonNode json = mapper.readTree(response.body());
            JsonNode embeddingArray = json.get("embedding");

            List<Float> embedding = new ArrayList<>();
            for (JsonNode node : embeddingArray) {
                embedding.add(node.floatValue());
            }

            return embedding;

        } catch (Exception e) {
            throw new RuntimeException("Embedding failed", e);
        }
    }

    private record EmbedRequest(String model, String prompt) {}
}