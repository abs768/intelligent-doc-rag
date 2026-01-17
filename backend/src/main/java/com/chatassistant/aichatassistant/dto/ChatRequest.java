package com.chatassistant.aichatassistant.dto;

import java.util.List;
import java.util.UUID;

public record ChatRequest(
        UUID conversationId,
        String message,
        List<String> selectedDocuments,
        boolean useRag
) {
}