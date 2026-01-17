package com.chatassistant.aichatassistant.service;

import com.chatassistant.aichatassistant.client.OllamaGenerateResponse;
import com.chatassistant.aichatassistant.dto.ChatRequest;
import com.chatassistant.aichatassistant.dto.ChatResponse;
import com.chatassistant.aichatassistant.entity.Conversation;
import com.chatassistant.aichatassistant.entity.Message;
import com.chatassistant.aichatassistant.entity.User;
import com.chatassistant.aichatassistant.repository.ConversationRepository;
import com.chatassistant.aichatassistant.repository.MessageRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final int HISTORY_LIMIT = 10;
    private static final int MAX_PROMPT_CHARS = 6000;
    private static final int RAG_CHUNK_LIMIT = 5;

    private final ConversationRepository conversationRepo;
    private final MessageRepository messageRepo;
    private final OllamaService ollamaService;
    private final DocumentService documentService;

    public ChatService(
            ConversationRepository conversationRepo,
            MessageRepository messageRepo,
            OllamaService ollamaService,
            DocumentService documentService
    ) {
        this.conversationRepo = conversationRepo;
        this.messageRepo = messageRepo;
        this.ollamaService = ollamaService;
        this.documentService = documentService;
    }

    @Transactional
    public ChatResponse chat(ChatRequest req) {
        if (req.message() == null || req.message().isBlank()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }

        UUID userId = getCurrentUserId();
        Conversation conv = getOrCreateConversation(req.conversationId(), userId);

        // 1️⃣ Save user message
        Message userMsg = Message.createUserMessage(conv, req.message());
        messageRepo.save(userMsg);

        // 2️⃣ Build prompt
        String prompt = req.useRag()
                ? buildRagPrompt(conv, req, userId)
                : buildChatPrompt(conv);

        // 3️⃣ Call LLM
        OllamaGenerateResponse llmResponse =
                ollamaService.generate(prompt);

        String answer = llmResponse.response();

        // 4️⃣ Save assistant reply
        Message assistantMsg = Message.createAssistantMessage(conv, answer);
        messageRepo.save(assistantMsg);

        return new ChatResponse(conv.getId(), answer);
    }

    // ---------------- CONVERSATION ----------------

    private Conversation getOrCreateConversation(UUID id, UUID userId) {
        if (id == null) {
            Conversation conv = Conversation.create(userId,
                    """
                    You are a helpful AI assistant.
                    Be concise, accurate, and honest.
                    If you don't know something, say so.
                    """
            );
            return conversationRepo.save(conv);
        }

        return conversationRepo.findByIdAndUserId(id, userId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Conversation not found or access denied"));
    }

    // ---------------- PROMPT BUILDING ----------------

    private String buildChatPrompt(Conversation conv) {
        List<Message> history =
                messageRepo.findTop10ByConversation_IdOrderByCreatedAtDesc(conv.getId());

        return buildPrompt(conv.getSystemPrompt(), history);
    }

    private String buildRagPrompt(Conversation conv, ChatRequest req, UUID userId) {
        // Convert String IDs to UUIDs safely
        List<UUID> docIds = null;
        if (req.selectedDocuments() != null) {
            docIds = req.selectedDocuments().stream()
                    .map(UUID::fromString)
                    .collect(Collectors.toList());
        }

        List<String> chunks = documentService.findRelevantChunks(
                userId,
                req.message(),
                RAG_CHUNK_LIMIT,
                docIds
        );

        String context = String.join("\n\n", chunks);
        if (context.length() > MAX_PROMPT_CHARS / 2) {
            context = context.substring(0, MAX_PROMPT_CHARS / 2);
        }

        List<Message> history =
                messageRepo.findTop10ByConversation_IdOrderByCreatedAtDesc(conv.getId());

        String basePrompt = buildPrompt(conv.getSystemPrompt(), history);

        return """
                %s

                You are answering based ONLY on the following document context.
                If the answer is not present, say:
                "I don't have that information in the provided documents."

                DOCUMENT CONTEXT:
                %s

                USER QUESTION:
                %s

                ASSISTANT:
                """.formatted(basePrompt, context, req.message());
    }

    private String buildPrompt(String system, List<Message> history) {
        StringBuilder sb = new StringBuilder();

        if (system != null && !system.isBlank()) {
            sb.append("SYSTEM: ").append(system).append("\n\n");
        }

        // History comes in Descending order (newest first), so we need to reverse it for the prompt
        int totalChars = sb.length();
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            String line = m.getRole() + ": " + m.getContent() + "\n";
            if (totalChars + line.length() > MAX_PROMPT_CHARS) break;
            sb.append(line);
            totalChars += line.length();
        }

        sb.append("ASSISTANT: ");
        return sb.toString();
    }

    private UUID getCurrentUserId() {
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return user.getId();
    }
}