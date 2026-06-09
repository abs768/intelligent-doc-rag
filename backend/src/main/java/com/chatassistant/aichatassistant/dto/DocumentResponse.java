package com.chatassistant.aichatassistant.dto;

import com.chatassistant.aichatassistant.entity.Document;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID documentId,
        String filename,
        String status,
        int chunkCount,
        Instant createdAt
) {
    public static DocumentResponse from(Document doc) {
        return new DocumentResponse(
                doc.getId(),
                doc.getFilename(),
                doc.getStatus().name(),
                doc.getChunkCount(),
                doc.getCreatedAt()
        );
    }
}
