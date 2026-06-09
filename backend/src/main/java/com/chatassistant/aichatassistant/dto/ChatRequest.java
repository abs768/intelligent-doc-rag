package com.chatassistant.aichatassistant.dto;

import java.util.List;
import java.util.UUID;

public record ChatRequest(
        UUID conversationId,
        String message,
        List<String> selectedDocuments,
        boolean useRag,
        String persona,   // Module 3: "Analyst", "Commercial Lead", "Technical Lead", "External Merchant"
        String language    // Module 3: "English", "Español"
) {
}
