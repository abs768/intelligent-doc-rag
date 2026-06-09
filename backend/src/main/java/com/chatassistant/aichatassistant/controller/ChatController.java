package com.chatassistant.aichatassistant.controller;

import com.chatassistant.aichatassistant.dto.ChatRequest;
import com.chatassistant.aichatassistant.dto.ChatResponse;
import com.chatassistant.aichatassistant.entity.User;
import com.chatassistant.aichatassistant.service.ChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(
            @AuthenticationPrincipal User user,
            @RequestBody ChatRequest req
    ) {
        if (req.message() == null || req.message().isBlank()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }

        ChatResponse response = chatService.chat(user.getId(), req);
        return ResponseEntity.ok(response);
    }

    /**
     * Grounded RAG endpoint — separate from /api/chat so the existing prose flow keeps
     * working unchanged. Returns a {@link ChatResponse} with non-null citations linked
     * to the actual retrieved chunks; refuses if no chunks match or the model fails
     * to cite anything.
     */
    @PostMapping("/grounded")
    public ResponseEntity<ChatResponse> chatGrounded(
            @AuthenticationPrincipal User user,
            @RequestBody ChatRequest req
    ) {
        if (req.message() == null || req.message().isBlank()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }
        return ResponseEntity.ok(chatService.chatGrounded(user.getId(), req));
    }
}
