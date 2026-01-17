package com.chatassistant.aichatassistant.service;

import com.chatassistant.aichatassistant.entity.Document;
import com.chatassistant.aichatassistant.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentService {

    private static final int CHUNK_SIZE = 500;

    private final DocumentRepository documentRepo;
    private final EmbeddingService embeddingService;
    private final QdrantService qdrantService;

    public DocumentService(
            DocumentRepository documentRepo,
            EmbeddingService embeddingService,
            QdrantService qdrantService
    ) {
        this.documentRepo = documentRepo;
        this.embeddingService = embeddingService;
        this.qdrantService = qdrantService;
    }

    // ---------------- INGEST ----------------

    @Transactional
    public UUID ingestDocument(UUID userId, String filename, String content) {
        // 1. Create DB Record
        Document doc = Document.create(userId, filename);
        documentRepo.save(doc);

        try {
            // 2. Chunk Content
            List<String> chunks = chunk(content);

            // 3. Embed and Upsert to Vector DB
            for (int i = 0; i < chunks.size(); i++) {
                List<Float> vector = embeddingService.embed(chunks.get(i));
                qdrantService.upsertChunk(
                        userId,
                        doc.getId(),
                        i,
                        vector,
                        chunks.get(i),
                        filename
                );
            }

            // 4. Update Status
            doc.setChunkCount(chunks.size());
            doc.setStatus(Document.Status.INGESTED);
            documentRepo.save(doc);

            return doc.getId();

        } catch (Exception e) {
            doc.setStatus(Document.Status.FAILED);
            documentRepo.save(doc);
            throw new RuntimeException("Ingestion failed", e);
        }
    }

    // ---------------- LIST ----------------

    public List<Document> listDocuments(UUID userId) {
        return documentRepo.findByUserId(userId);
    }

    // ---------------- RAG SEARCH ----------------

    public List<String> findRelevantChunks(
            UUID userId,
            String query,
            int limit,
            List<UUID> documentIds
    ) {
        if (query == null || query.isBlank()) return List.of();

        // 🔒 Verify ownership of requested docs
        if (documentIds != null && !documentIds.isEmpty()) {
            for (UUID docId : documentIds) {
                boolean exists = documentRepo.findByIdAndUserId(docId, userId).isPresent();
                if (!exists) {
                    throw new IllegalArgumentException("Access denied for document: " + docId);
                }
            }
        }

        List<Float> queryVector = embeddingService.embed(query);

        return qdrantService.search(
                userId,
                queryVector,
                limit,
                documentIds
        );
    }

    // ---------------- DELETE ----------------

    @Transactional
    public void deleteDocument(UUID userId, UUID documentId) {
        // Verify ownership
        Document doc = documentRepo.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));

        // Delete from Vector DB
        qdrantService.deleteDocument(userId, documentId);

        // Delete from SQL DB
        documentRepo.delete(doc);
    }

    @Transactional
    public void deleteAllForUser(UUID userId) {
        // Delete all from Vector DB
        qdrantService.deleteAllForUser(userId);

        // Delete all from SQL DB
        List<Document> docs = documentRepo.findByUserId(userId);
        documentRepo.deleteAll(docs);
    }

    // ---------------- HELPERS ----------------

    private List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder buf = new StringBuilder();

        for (String w : words) {
            if (buf.length() + w.length() > CHUNK_SIZE) {
                chunks.add(buf.toString());
                buf.setLength(0);
            }
            buf.append(w).append(" ");
        }

        if (!buf.isEmpty()) chunks.add(buf.toString());
        return chunks;
    }
}