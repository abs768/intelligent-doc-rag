package com.chatassistant.aichatassistant.service;

import com.chatassistant.aichatassistant.entity.Document;
import com.chatassistant.aichatassistant.exception.BadRequestException;
import com.chatassistant.aichatassistant.exception.ServiceUnavailableException;
import com.chatassistant.aichatassistant.rag.DocumentTextExtractor;
import com.chatassistant.aichatassistant.rag.IngestionPipeline;
import com.chatassistant.aichatassistant.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Document lifecycle: upload, list and delete.
 *
 * <p>The RAG work itself lives in the {@code rag} package: {@link DocumentTextExtractor}
 * loads text and {@link IngestionPipeline} splits, embeds and stores it. Retrieval is
 * {@code rag.RagRetriever}.
 */
@Service
public class DocumentService {

    private final DocumentRepository documentRepo;
    private final DocumentTextExtractor extractor;
    private final IngestionPipeline ingestionPipeline;
    private final QdrantService qdrantService;

    public DocumentService(
            DocumentRepository documentRepo,
            DocumentTextExtractor extractor,
            IngestionPipeline ingestionPipeline,
            QdrantService qdrantService
    ) {
        this.documentRepo = documentRepo;
        this.extractor = extractor;
        this.ingestionPipeline = ingestionPipeline;
        this.qdrantService = qdrantService;
    }

    // ======================== SMALL FILES (synchronous) ========================

    /** Extracts, chunks, embeds and stores a small upload before returning its id. */
    @Transactional
    public UUID ingestDocument(UUID userId, MultipartFile file) {
        String filename = file.getOriginalFilename();
        String content = extractor.extract(file);

        if (content == null || content.isBlank()) {
            throw new BadRequestException("Could not extract text from file: " + filename);
        }

        Document doc = Document.create(userId, filename);
        documentRepo.save(doc);

        try {
            int chunkCount = ingestionPipeline.ingestText(userId, doc.getId(), filename, content);
            doc.setChunkCount(chunkCount);
            doc.setStatus(Document.Status.INGESTED);
            documentRepo.save(doc);
            return doc.getId();

        } catch (Exception e) {
            doc.setStatus(Document.Status.FAILED);
            documentRepo.save(doc);
            throw new ServiceUnavailableException("Ingestion failed for: " + filename, e);
        }
    }

    // ======================== LARGE FILES (chunked upload, async ingestion) ========================

    /** Appends one slice of a large upload to its temp file on disk. */
    public void appendChunk(UUID uploadId, byte[] data) {
        File tempFile = getTempFile(uploadId);
        try (FileOutputStream fos = new FileOutputStream(tempFile, true)) {
            fos.write(data);
        } catch (IOException e) {
            throw new ServiceUnavailableException("Failed to write chunk for uploadId=" + uploadId, e);
        }
    }

    /**
     * Records the document as PROCESSING and hands ingestion to a background thread.
     * Returns the document id immediately, so the caller can respond 202 Accepted.
     *
     * <p>Not {@code @Transactional}: the PROCESSING row must be committed before the
     * background thread looks it up to record the final status.
     */
    public UUID finalizeUpload(UUID userId, UUID uploadId, String filename) {
        File tempFile = getTempFile(uploadId);
        if (!tempFile.exists() || tempFile.length() == 0) {
            throw new BadRequestException("No upload data found for uploadId=" + uploadId);
        }

        Document doc = Document.create(userId, filename);
        doc.setStatus(Document.Status.PROCESSING);
        documentRepo.save(doc);

        ingestionPipeline.ingestFileAsync(userId, doc.getId(), filename, tempFile);
        return doc.getId();
    }

    // ======================== LIST / DELETE ========================

    public List<Document> listDocuments(UUID userId) {
        return documentRepo.findByUserId(userId);
    }

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

    private File getTempFile(UUID uploadId) {
        return new File(System.getProperty("java.io.tmpdir"), "docgpt-upload-" + uploadId);
    }
}
