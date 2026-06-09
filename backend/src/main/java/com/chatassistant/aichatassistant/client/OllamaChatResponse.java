package com.chatassistant.aichatassistant.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for Ollama's /api/chat endpoint.
 * Timing fields are returned by Ollama in nanoseconds — the benchmark module reads them
 * to compute time-to-first-token and tokens/sec without separate streaming.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OllamaChatResponse(
        OllamaChatMessage message,
        boolean done,
        @JsonProperty("total_duration")       Long totalDurationNs,
        @JsonProperty("load_duration")        Long loadDurationNs,
        @JsonProperty("prompt_eval_count")    Integer promptEvalCount,
        @JsonProperty("prompt_eval_duration") Long promptEvalDurationNs,
        @JsonProperty("eval_count")           Integer evalCount,
        @JsonProperty("eval_duration")        Long evalDurationNs
) {
    public record OllamaChatMessage(String role, String content) {}
}
