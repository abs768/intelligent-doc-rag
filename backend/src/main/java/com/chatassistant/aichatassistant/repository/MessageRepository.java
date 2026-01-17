package com.chatassistant.aichatassistant.repository;

import com.chatassistant.aichatassistant.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findTop10ByConversation_IdOrderByCreatedAtDesc(UUID conversationId);
}

