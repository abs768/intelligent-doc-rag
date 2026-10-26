package com.chatassistant.aichatassistant.rag;

import com.chatassistant.aichatassistant.dto.RetrievalResult;
import com.chatassistant.aichatassistant.dto.RetrievedChunk;
import com.chatassistant.aichatassistant.repository.DocumentRepository;
import com.chatassistant.aichatassistant.service.EmbeddingService;
import com.chatassistant.aichatassistant.service.QdrantService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * RAG stage 5 — retrieve. Embeds a question and returns the user's most similar chunks.
 *
 * <p>Every call checks that any selected documents belong to the user before searching,
 * and {@link QdrantService} always filters by {@code userId}, so one user can never
 * retrieve another user's chunks. Python equivalent: {@code search.RAGRetriever.retrieve}.
 */
@Component
public class RagRetriever {

    private final DocumentRepository documentRepo;
    private final EmbeddingService embeddingService;
    private final QdrantService qdrantService;

    public RagRetriever(
            DocumentRepository documentRepo,
            EmbeddingService embeddingService,
            QdrantService qdrantService
    ) {
        this.documentRepo = documentRepo;
        this.embeddingService = embeddingService;
        this.qdrantService = qdrantService;
    }

    /** Returns the top-k chunk texts for a question. */
    public List<String> findRelevantChunks(UUID userId, String query, int limit, List<UUID> documentIds) {
        if (isBlank(query)) return List.of();
        requireOwnership(userId, documentIds);
        return qdrantService.search(userId, embeddingService.embed(query), limit, documentIds);
    }

    /** Returns the top-k chunk texts together with their source filenames. */
    public RetrievalResult findRelevantChunksWithSources(
            UUID userId, String query, int limit, List<UUID> documentIds
    ) {
        if (isBlank(query)) return new RetrievalResult(List.of(), List.of());
        requireOwnership(userId, documentIds);
        return qdrantService.searchWithSources(userId, embeddingService.embed(query), limit, documentIds);
    }

    /** Returns the top-k chunks with full metadata, for building citations. */
    public List<RetrievedChunk> findRelevantChunksForCitations(
            UUID userId, String query, int limit, List<UUID> documentIds
    ) {
        if (isBlank(query)) return List.of();
        requireOwnership(userId, documentIds);
        return qdrantService.searchWithCitations(userId, embeddingService.embed(query), limit, documentIds);
    }

    private void requireOwnership(UUID userId, List<UUID> documentIds) {
        if (documentIds == null) return;
        for (UUID docId : documentIds) {
            documentRepo.findByIdAndUserId(docId, userId)
                    .orElseThrow(() -> new IllegalArgumentException("Access denied for document: " + docId));
        }
    }

    private static boolean isBlank(String query) {
        return query == null || query.isBlank();
    }
}
