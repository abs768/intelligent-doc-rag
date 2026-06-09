package com.chatassistant.aichatassistant.service;

import com.chatassistant.aichatassistant.dto.RetrievalResult;
import com.chatassistant.aichatassistant.entity.Document;
import com.chatassistant.aichatassistant.exception.BadRequestException;
import com.chatassistant.aichatassistant.exception.ServiceUnavailableException;
import com.chatassistant.aichatassistant.repository.DocumentRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);

    private static final int CHUNK_SIZE_CHARS = 1500;
    private static final int CHUNK_OVERLAP_CHARS = 200;

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

    // ======================== INGEST (original — for small files) ========================

    @Transactional
    public UUID ingestDocument(UUID userId, MultipartFile file) {
        String filename = file.getOriginalFilename();
        String content = extractText(file);

        if (content == null || content.isBlank()) {
            throw new BadRequestException("Could not extract text from file: " + filename);
        }

        Document doc = Document.create(userId, filename);
        documentRepo.save(doc);

        try {
            List<String> chunks = chunkByCharacters(content);
            logger.info("Document '{}' split into {} chunks for userId={}", filename, chunks.size(), userId);

            for (int i = 0; i < chunks.size(); i++) {
                List<Float> vector = embeddingService.embed(chunks.get(i));
                qdrantService.upsertChunk(userId, doc.getId(), i, vector, chunks.get(i), filename);
            }

            doc.setChunkCount(chunks.size());
            doc.setStatus(Document.Status.INGESTED);
            documentRepo.save(doc);
            return doc.getId();

        } catch (Exception e) {
            doc.setStatus(Document.Status.FAILED);
            documentRepo.save(doc);
            throw new ServiceUnavailableException("Ingestion failed for: " + filename, e);
        }
    }

    // ======================== CHUNKED UPLOAD ASSEMBLY ========================

    /**
     * Appends a chunk of bytes to a temporary file on disk.
     * Called by the controller for each 5MB slice from the frontend.
     */
    public void appendChunk(UUID uploadId, byte[] data) {
        File tempFile = getTempFile(uploadId);
        try (FileOutputStream fos = new FileOutputStream(tempFile, true)) {
            fos.write(data);
        } catch (IOException e) {
            throw new ServiceUnavailableException("Failed to write chunk for uploadId=" + uploadId, e);
        }
    }

    /**
     * Creates the DB record and kicks off async embedding.
     * Returns the documentId immediately — caller responds 202 Accepted.
     */
    @Transactional
    public UUID finalizeUpload(UUID userId, UUID uploadId, String filename) {
        File tempFile = getTempFile(uploadId);
        if (!tempFile.exists() || tempFile.length() == 0) {
            throw new BadRequestException("No upload data found for uploadId=" + uploadId);
        }

        Document doc = Document.create(userId, filename);
        doc.setStatus(Document.Status.PROCESSING);
        documentRepo.save(doc);

        // Fire-and-forget: embedding runs on a separate thread
        processEmbeddingsAsync(userId, doc.getId(), filename, tempFile);

        return doc.getId();
    }

    // ======================== ASYNC EMBEDDING ========================

    /**
     * Heavy lifting runs off the HTTP thread.
     * Reads the assembled temp file, extracts text, chunks, embeds, upserts to Qdrant.
     */
    @Async
    public void processEmbeddingsAsync(UUID userId, UUID documentId, String filename, File tempFile) {
        logger.info("Async embedding started for '{}' (docId={})", filename, documentId);

        try {
            String content = extractTextFromFile(tempFile, filename);
            if (content == null || content.isBlank()) {
                updateStatus(documentId, Document.Status.FAILED, 0);
                return;
            }

            List<String> chunks = chunkByCharacters(content);
            logger.info("Document '{}' split into {} chunks for async embedding", filename, chunks.size());

            for (int i = 0; i < chunks.size(); i++) {
                List<Float> vector = embeddingService.embed(chunks.get(i));
                qdrantService.upsertChunk(userId, documentId, i, vector, chunks.get(i), filename);
            }

            updateStatus(documentId, Document.Status.INGESTED, chunks.size());
            logger.info("Async embedding complete for '{}' ({} chunks)", filename, chunks.size());

        } catch (Exception e) {
            logger.error("Async embedding failed for '{}': {}", filename, e.getMessage(), e);
            updateStatus(documentId, Document.Status.FAILED, 0);
        } finally {
            // Clean up temp file
            if (tempFile.exists()) tempFile.delete();
        }
    }

    private void updateStatus(UUID documentId, Document.Status status, int chunkCount) {
        documentRepo.findById(documentId).ifPresent(doc -> {
            doc.setStatus(status);
            doc.setChunkCount(chunkCount);
            documentRepo.save(doc);
        });
    }

    // ======================== LIST ========================

    public List<Document> listDocuments(UUID userId) {
        return documentRepo.findByUserId(userId);
    }

    // ======================== RAG SEARCH ========================

    public List<String> findRelevantChunks(
            UUID userId, String query, int limit, List<UUID> documentIds
    ) {
        if (query == null || query.isBlank()) return List.of();

        if (documentIds != null && !documentIds.isEmpty()) {
            for (UUID docId : documentIds) {
                documentRepo.findByIdAndUserId(docId, userId)
                        .orElseThrow(() -> new IllegalArgumentException("Access denied for document: " + docId));
            }
        }

        List<Float> queryVector = embeddingService.embed(query);
        return qdrantService.search(userId, queryVector, limit, documentIds);
    }

    public RetrievalResult findRelevantChunksWithSources(
            UUID userId, String query, int limit, List<UUID> documentIds
    ) {
        if (query == null || query.isBlank()) return new RetrievalResult(List.of(), List.of());

        if (documentIds != null && !documentIds.isEmpty()) {
            for (UUID docId : documentIds) {
                documentRepo.findByIdAndUserId(docId, userId)
                        .orElseThrow(() -> new IllegalArgumentException("Access denied for document: " + docId));
            }
        }

        List<Float> queryVector = embeddingService.embed(query);
        return qdrantService.searchWithSources(userId, queryVector, limit, documentIds);
    }

    // ======================== DELETE ========================

    @Transactional
    public void deleteDocument(UUID userId, UUID documentId) {
        Document doc = documentRepo.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        qdrantService.deleteDocument(userId, documentId);
        documentRepo.delete(doc);
    }

    @Transactional
    public void deleteAllForUser(UUID userId) {
        qdrantService.deleteAllForUser(userId);
        List<Document> docs = documentRepo.findByUserId(userId);
        documentRepo.deleteAll(docs);
    }

    // ======================== TEXT EXTRACTION ========================

    private String extractText(MultipartFile file) {
        String filename = file.getOriginalFilename();
        try {
            if (filename != null && filename.toLowerCase().endsWith(".pdf")) {
                return extractTextFromPdf(file);
            }
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BadRequestException("Failed to read file: " + filename);
        }
    }

    private String extractTextFromPdf(MultipartFile file) throws Exception {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * Extract text from an assembled temp file on disk.
     */
    private String extractTextFromFile(File file, String filename) {
        try {
            if (filename != null && filename.toLowerCase().endsWith(".pdf")) {
                try (PDDocument document = Loader.loadPDF(file)) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    return stripper.getText(document);
                }
            }
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("Text extraction failed for '{}': {}", filename, e.getMessage());
            return null;
        }
    }

    // ======================== CHUNKING ========================

    private List<String> chunkByCharacters(String text) {
        List<String> chunks = new ArrayList<>();
        String[] words = text.split("\\s+");

        List<String> currentChunk = new ArrayList<>();
        int currentLength = 0;

        for (String word : words) {
            currentChunk.add(word);
            currentLength += word.length() + 1;

            if (currentLength >= CHUNK_SIZE_CHARS) {
                chunks.add(String.join(" ", currentChunk));

                int overlapLength = 0;
                int overlapStart = currentChunk.size();
                while (overlapStart > 0 && overlapLength < CHUNK_OVERLAP_CHARS) {
                    overlapStart--;
                    overlapLength += currentChunk.get(overlapStart).length() + 1;
                }

                currentChunk = new ArrayList<>(currentChunk.subList(overlapStart, currentChunk.size()));
                currentLength = overlapLength;
            }
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(String.join(" ", currentChunk));
        }
        return chunks;
    }

    // ======================== TEMP FILE HELPERS ========================

    private File getTempFile(UUID uploadId) {
        return new File(System.getProperty("java.io.tmpdir"), "docgpt-upload-" + uploadId);
    }
}
