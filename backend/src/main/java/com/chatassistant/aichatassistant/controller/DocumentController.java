package com.chatassistant.aichatassistant.controller;

import com.chatassistant.aichatassistant.dto.DocumentResponse;
import com.chatassistant.aichatassistant.dto.MessageResponse;
import com.chatassistant.aichatassistant.dto.UploadResponse;
import com.chatassistant.aichatassistant.entity.User;
import com.chatassistant.aichatassistant.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    // ======================== ORIGINAL UPLOAD (small files) ========================

    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> uploadDocument(
            @AuthenticationPrincipal User user,
            @RequestParam("file") MultipartFile file
    ) {
        UUID documentId = documentService.ingestDocument(user.getId(), file);

        return ResponseEntity.ok(new UploadResponse(
                documentId,
                file.getOriginalFilename(),
                "Document uploaded and indexed successfully"
        ));
    }

    // ======================== CHUNKED UPLOAD (large files) ========================

    /**
     * POST /api/documents/upload-chunk
     * Receives one 5MB slice of a large file. The frontend sends these sequentially.
     * Each chunk is appended to a temp file identified by uploadId.
     */
    @PostMapping("/upload-chunk")
    public ResponseEntity<Map<String, String>> uploadChunk(
            @AuthenticationPrincipal User user,
            @RequestParam("uploadId") UUID uploadId,
            @RequestParam("chunk") MultipartFile chunk
    ) {
        documentService.appendChunk(uploadId, getBytesOrThrow(chunk));
        return ResponseEntity.ok(Map.of("status", "chunk_received"));
    }

    /**
     * POST /api/documents/upload-finalize
     * Called after all chunks are uploaded. Assembles the file, creates the DB record,
     * and kicks off @Async embedding. Returns 202 Accepted immediately.
     */
    @PostMapping("/upload-finalize")
    public ResponseEntity<UploadResponse> finalizeUpload(
            @AuthenticationPrincipal User user,
            @RequestParam("uploadId") UUID uploadId,
            @RequestParam("filename") String filename
    ) {
        UUID documentId = documentService.finalizeUpload(user.getId(), uploadId, filename);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new UploadResponse(
                documentId,
                filename,
                "File received. Embedding in progress..."
        ));
    }

    // ======================== LIST / DELETE ========================

    @GetMapping("/list")
    public ResponseEntity<List<DocumentResponse>> listDocuments(
            @AuthenticationPrincipal User user
    ) {
        List<DocumentResponse> docs = documentService.listDocuments(user.getId())
                .stream()
                .map(DocumentResponse::from)
                .toList();

        return ResponseEntity.ok(docs);
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<MessageResponse> deleteDocument(
            @AuthenticationPrincipal User user,
            @PathVariable UUID documentId
    ) {
        documentService.deleteDocument(user.getId(), documentId);
        return ResponseEntity.ok(new MessageResponse("Document deleted"));
    }

    @DeleteMapping("/all")
    public ResponseEntity<MessageResponse> deleteAllDocuments(
            @AuthenticationPrincipal User user
    ) {
        documentService.deleteAllForUser(user.getId());
        return ResponseEntity.ok(new MessageResponse("All documents deleted for user"));
    }

    // ======================== HELPERS ========================

    private byte[] getBytesOrThrow(MultipartFile chunk) {
        try {
            return chunk.getBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read chunk bytes", e);
        }
    }
}
