package com.chatassistant.aichatassistant.dto;

import java.util.UUID;

public record UploadResponse(
        UUID documentId,
        String filename,
        String message
) {
}
