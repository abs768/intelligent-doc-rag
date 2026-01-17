package com.chatassistant.aichatassistant.repository;

import com.chatassistant.aichatassistant.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    // 🔒 Enforces ownership (used by ChatService)
    Optional<Conversation> findByIdAndUserId(UUID id, UUID userId);

    // 📜 Required for conversation listing / UI
    List<Conversation> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
