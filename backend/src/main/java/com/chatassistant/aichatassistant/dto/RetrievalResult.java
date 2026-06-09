package com.chatassistant.aichatassistant.dto;

import java.util.List;

/**
 * Holds both the retrieved chunk texts and their source filenames.
 * Used by QdrantService.searchWithSources() for the RAG pipeline.
 */
public record RetrievalResult(
        List<String> chunks,
        List<String> filenames
) {
}
