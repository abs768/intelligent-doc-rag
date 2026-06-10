package com.chatassistant.aichatassistant.dto;

import java.util.UUID;

/**
 * One chunk returned from Qdrant vector search, with enough metadata to build a
 * verifiable citation.
 *
 * Page numbers are deliberately NOT carried here. Adding page-aware citations
 * requires rewriting the PDF extractor to track page boundaries during chunking;
 * until that exists, a page field would always be null and would read as
 * unfinished. When page-aware chunking lands, add an Integer page here and in
 * Citation in the same change.
 */
public record RetrievedChunk(
        UUID documentId,
        String filename,
        int chunkIndex,
        String content,
        float score
) {}
