package com.chatassistant.aichatassistant.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_doc_user", columnList = "user_id")
})
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String filename;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int chunkCount;

    @Column(nullable = false)
    private Instant createdAt;

    public enum Status {
        PENDING, PROCESSING, INGESTED, FAILED
    }

    protected Document() {}

    public static Document create(UUID userId, String filename) {
        Document d = new Document();
        d.userId = userId;
        d.filename = filename;
        d.status = Status.PENDING; // Default status
        d.chunkCount = 0;
        d.createdAt = Instant.now();
        return d;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getFilename() { return filename; }
    public Status getStatus() { return status; }
    public int getChunkCount() { return chunkCount; }
    public Instant getCreatedAt() { return createdAt; }

    // Setters for updating after ingestion
    public void setStatus(Status status) { this.status = status; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
}