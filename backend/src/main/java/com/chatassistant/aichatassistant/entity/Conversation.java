package com.chatassistant.aichatassistant.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "conversations")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Conversation() {}

    public static Conversation create(UUID userId, String systemPrompt) {
        Conversation c = new Conversation();
        c.userId = userId;
        c.systemPrompt = systemPrompt;
        c.createdAt = Instant.now();
        return c;
    }

    // ✅ READ ACCESSORS (REQUIRED)
    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
