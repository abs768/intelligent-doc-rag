package com.chatassistant.aichatassistant.rag;

import com.chatassistant.aichatassistant.entity.Document;
import com.chatassistant.aichatassistant.repository.DocumentRepository;
import com.chatassistant.aichatassistant.service.EmbeddingService;
import com.chatassistant.aichatassistant.service.QdrantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.UUID;

/**
 * RAG stages 2–4 for one document — split, embed, store.
 *
 * <p>Owns the write side of the pipeline: {@link TextChunker} → {@link EmbeddingService}
 * → {@link QdrantService}. Python equivalent: {@code EmbeddingPipeline} plus
 * {@code VectorStore.add_documents}.
 *
 * <p>This is a separate bean from {@code DocumentService} on purpose: Spring applies
 * {@code @Async} through a proxy, so the async method only runs off the request thread
 * when it is called from another bean.
 */
@Service
public class IngestionPipeline {

    private static final Logger logger = LoggerFactory.getLogger(IngestionPipeline.class);

    private final DocumentTextExtractor extractor;
    private final TextChunker chunker;
    private final EmbeddingService embeddingService;
    private final QdrantService qdrantService;
    private final DocumentRepository documentRepo;

    public IngestionPipeline(
            DocumentTextExtractor extractor,
            TextChunker chunker,
            EmbeddingService embeddingService,
            QdrantService qdrantService,
            DocumentRepository documentRepo
    ) {
        this.extractor = extractor;
        this.chunker = chunker;
        this.embeddingService = embeddingService;
        this.qdrantService = qdrantService;
        this.documentRepo = documentRepo;
    }

    /** Chunks, embeds and upserts one document's text; returns the number of chunks stored. */
    public int ingestText(UUID userId, UUID documentId, String filename, String content) {
        List<String> chunks = chunker.chunk(content);
        logger.info("Document '{}' split into {} chunks for userId={}", filename, chunks.size(), userId);

        for (int i = 0; i < chunks.size(); i++) {
            List<Float> vector = embeddingService.embed(chunks.get(i));
            qdrantService.upsertChunk(userId, documentId, i, vector, chunks.get(i), filename);
        }
        return chunks.size();
    }

    /**
     * Runs the whole pipeline for an assembled upload off the request thread, then records
     * INGESTED or FAILED on the document row and deletes the temp file.
     */
    @Async
    public void ingestFileAsync(UUID userId, UUID documentId, String filename, File tempFile) {
        logger.info("Async ingestion started for '{}' (docId={})", filename, documentId);
        try {
            String content = extractor.extract(tempFile, filename);
            if (content == null || content.isBlank()) {
                updateStatus(documentId, Document.Status.FAILED, 0);
                return;
            }

            int chunkCount = ingestText(userId, documentId, filename, content);
            updateStatus(documentId, Document.Status.INGESTED, chunkCount);
            logger.info("Async ingestion complete for '{}' ({} chunks)", filename, chunkCount);

        } catch (Exception e) {
            logger.error("Async ingestion failed for '{}': {}", filename, e.getMessage(), e);
            updateStatus(documentId, Document.Status.FAILED, 0);
        } finally {
            if (tempFile.exists() && !tempFile.delete()) {
                logger.warn("Could not delete temp upload file {}", tempFile);
            }
        }
    }

    private void updateStatus(UUID documentId, Document.Status status, int chunkCount) {
        documentRepo.findById(documentId).ifPresent(doc -> {
            doc.setStatus(status);
            doc.setChunkCount(chunkCount);
            documentRepo.save(doc);
        });
    }
}
