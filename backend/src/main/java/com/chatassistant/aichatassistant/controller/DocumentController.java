package com.chatassistant.aichatassistant.controller;

import com.chatassistant.aichatassistant.entity.Document;
import com.chatassistant.aichatassistant.entity.User;
import com.chatassistant.aichatassistant.service.DocumentService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
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

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @AuthenticationPrincipal User user,
            @RequestParam("file") MultipartFile file
    ) {
        try {
            if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

            String filename = file.getOriginalFilename();
            String content;

            if (filename != null && filename.toLowerCase().endsWith(".pdf")) {
                content = extractTextFromPdf(file);
            } else {
                content = new String(file.getBytes(), StandardCharsets.UTF_8);
            }

            UUID documentId = documentService.ingestDocument(user.getId(), filename, content);

            return ResponseEntity.ok(Map.of(
                    "documentId", documentId,
                    "filename", filename,
                    "message", "Document uploaded and indexed successfully"
            ));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> listDocuments(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(401).body(List.of());

        List<Document> docs = documentService.listDocuments(user.getId());

        List<Map<String, Object>> out = docs.stream().map(d -> Map.<String, Object>of(
                "documentId", d.getId(),
                "filename", d.getFilename(),
                "status", d.getStatus().name(),
                "chunkCount", d.getChunkCount(),
                "createdAt", d.getCreatedAt().toString()
        )).toList();

        return ResponseEntity.ok(out);
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Map<String, Object>> deleteDocument(
            @AuthenticationPrincipal User user,
            @PathVariable UUID documentId
    ) {
        try {
            if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

            documentService.deleteDocument(user.getId(), documentId);
            return ResponseEntity.ok(Map.of("message", "Document deleted", "documentId", documentId));

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Delete failed"));
        }
    }

    @DeleteMapping("/all")
    public ResponseEntity<Map<String, Object>> deleteAllDocuments(@AuthenticationPrincipal User user) {
        try {
            if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

            documentService.deleteAllForUser(user.getId());
            return ResponseEntity.ok(Map.of("message", "All documents deleted for user"));

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Delete failed"));
        }
    }

    private String extractTextFromPdf(MultipartFile file) throws Exception {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }
}