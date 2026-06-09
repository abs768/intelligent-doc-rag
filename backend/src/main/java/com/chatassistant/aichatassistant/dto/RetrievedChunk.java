package com.chatassistant.aichatassistant.dto;

import java.util.UUID;

/**
 * One chunk returned from Qdrant vector search, with enough metadata to build a
 * verifiable citation. {@code page} is currently null — chunks are not page-aware
 * yet (the PDF extractor strips text without page boundaries).
 */
public record RetrievedChunk(
        UUID documentId,
        String filename,
        int chunkIndex,
        String content,
        Integer page,
        float score
) {}
