package com.chatassistant.aichatassistant.client;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Request DTO for Ollama's /api/chat endpoint.
 * Uses proper role-based messages (system, user, assistant) instead of raw text prompts.
 * The optional {@code format} field ("json") forces Ollama to emit valid JSON — used by
 * the structured-output path.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OllamaChatRequest(
        String model,
        List<OllamaChatMessage> messages,
        boolean stream,
        String format,
        Map<String, Object> options
) {
    public record OllamaChatMessage(String role, String content) {}
}
