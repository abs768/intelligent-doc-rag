package com.chatassistant.aichatassistant.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "messages")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id")
    private Conversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public enum Role {
        USER, ASSISTANT
    }

    protected Message() {}

    public static Message createUserMessage(Conversation conversation, String content) {
        Message m = new Message();
        m.conversation = conversation;
        m.role = Role.USER;
        m.content = content;
        m.createdAt = Instant.now();
        return m;
    }

    public static Message createAssistantMessage(Conversation conversation, String content) {
        Message m = new Message();
        m.conversation = conversation;
        m.role = Role.ASSISTANT;
        m.content = content;
        m.createdAt = Instant.now();
        return m;
    }

    // ✅ READ ACCESSORS
    public Long getId() {
        return id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
