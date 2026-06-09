package com.chatassistant.aichatassistant.dto;

import java.util.List;
import java.util.UUID;

/**
 * Unified chat response.
 *
 * The existing prose path leaves {@code citations} null. The grounded-RAG path
 * populates it with one entry per chunk the model cited (and only chunks that were
 * actually retrieved — citation indices that fall outside the retrieved set are
 * filtered in ChatService).
 */
public record ChatResponse(
        UUID conversationId,
        String response,
        long retrievalLatencyMs,
        long llmInferenceLatencyMs,
        List<String> sources,
        List<Citation> citations
) {
    /** Backwards-compatible constructor for the existing prose / non-grounded path. */
    public ChatResponse(
            UUID conversationId,
            String response,
            long retrievalLatencyMs,
            long llmInferenceLatencyMs,
            List<String> sources
    ) {
        this(conversationId, response, retrievalLatencyMs, llmInferenceLatencyMs, sources, null);
    }
}
