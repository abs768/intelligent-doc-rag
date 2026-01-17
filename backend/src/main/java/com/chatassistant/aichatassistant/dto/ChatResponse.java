package com.chatassistant.aichatassistant.dto;

import java.util.UUID;

public record ChatResponse(
        UUID conversationId,
        String response
) {
}
