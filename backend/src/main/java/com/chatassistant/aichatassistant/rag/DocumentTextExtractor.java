package com.chatassistant.aichatassistant.rag;

import com.chatassistant.aichatassistant.exception.BadRequestException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * RAG stage 1 — load. Turns an uploaded file into plain text.
 *
 * <p>PDFs are read with PDFBox; every other file is read as UTF-8 text.
 * Python equivalent: {@code data_loader.load_all_documents}.
 */
@Component
public class DocumentTextExtractor {

    private static final Logger logger = LoggerFactory.getLogger(DocumentTextExtractor.class);

    /** Extracts text from an in-memory upload; throws BadRequestException if unreadable. */
    public String extract(MultipartFile file) {
        String filename = file.getOriginalFilename();
        try {
            if (isPdf(filename)) {
                try (PDDocument document = Loader.loadPDF(file.getBytes())) {
                    return new PDFTextStripper().getText(document);
                }
            }
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BadRequestException("Failed to read file: " + filename);
        }
    }

    /** Extracts text from an assembled file on disk; returns null if unreadable. */
    public String extract(File file, String filename) {
        try {
            if (isPdf(filename)) {
                try (PDDocument document = Loader.loadPDF(file)) {
                    return new PDFTextStripper().getText(document);
                }
            }
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("Text extraction failed for '{}': {}", filename, e.getMessage());
            return null;
        }
    }

    private static boolean isPdf(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".pdf");
    }
}
