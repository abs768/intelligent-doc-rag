package com.chatassistant.aichatassistant.dto;

import java.util.List;
import java.util.UUID;

public record ChatResponse(
        UUID conversationId,
        String response,
        long retrievalLatencyMs,    // Module 1: Qdrant search time
        long llmInferenceLatencyMs, // Module 1: Ollama inference time
        List<String> sources        // Module 2: filenames of retrieved chunks
) {
}
