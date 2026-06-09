package com.chatassistant.aichatassistant.service;

import com.chatassistant.aichatassistant.dto.ChatRequest;
import com.chatassistant.aichatassistant.dto.ChatResponse;
import com.chatassistant.aichatassistant.dto.RetrievalResult;
import com.chatassistant.aichatassistant.entity.Conversation;
import com.chatassistant.aichatassistant.entity.Message;
import com.chatassistant.aichatassistant.repository.ConversationRepository;
import com.chatassistant.aichatassistant.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);
    private static final int MAX_PROMPT_CHARS = 12000;
    private static final int RAG_CHUNK_LIMIT = 10;

    private static final String GENERAL_SYSTEM_PROMPT = """
            You are a helpful AI assistant. Be concise, accurate, and honest.
            If you don't know something, say so.""";

    private static final String RAG_SYSTEM_PROMPT = """
            You are a strict data extraction assistant. Your rules:
            1. Answer the user's question using ONLY the provided CONTEXT below.
            2. Quote specific numbers, names, percentages, and metrics exactly as they appear in the context.
            3. If the answer is not found in the context, reply exactly: "The provided document does not contain this information."
            4. DO NOT invent, estimate, or fabricate any numbers, names, or metrics.
            5. Use bullet points for clarity when listing multiple items.""";

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
    public ChatResponse chat(UUID userId, ChatRequest req) {
        if (req.message() == null || req.message().isBlank()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }

        Conversation conv = getOrCreateConversation(req.conversationId(), userId);

        // 1. Save user message
        Message userMsg = Message.createUserMessage(conv, req.message());
        messageRepo.save(userMsg);

        // 2. Build and call — RAG path includes telemetry + sources
        String answer;
        long retrievalMs = 0;
        long llmMs = 0;
        List<String> sources = List.of();

        if (req.useRag()) {
            // --- Module 1: Qdrant retrieval with stopwatch ---
            long t0 = System.currentTimeMillis();

            List<UUID> docIds = null;
            if (req.selectedDocuments() != null) {
                docIds = req.selectedDocuments().stream()
                        .map(UUID::fromString)
                        .collect(Collectors.toList());
            }

            RetrievalResult retrieval = documentService.findRelevantChunksWithSources(
                    userId, req.message(), RAG_CHUNK_LIMIT, docIds
            );

            long t1 = System.currentTimeMillis();
            retrievalMs = t1 - t0;

            // --- Module 2: Sources from retrieval ---
            sources = retrieval.filenames();

            // Build context
            String context = String.join("\n\n---\n\n", retrieval.chunks());
            if (context.length() > MAX_PROMPT_CHARS / 2) {
                context = context.substring(0, MAX_PROMPT_CHARS / 2);
            }

            // --- Module 3: Persona + Language injection ---
            String systemPrompt = buildPersonalizedSystemPrompt(
                    RAG_SYSTEM_PROMPT, req.persona(), req.language()
            );
            String userMessage = "CONTEXT:\n" + context + "\n\nQUESTION:\n" + req.message();

            // --- Module 1: LLM inference with stopwatch ---
            long t2 = System.currentTimeMillis();
            answer = ollamaService.chat(systemPrompt, userMessage);
            long t3 = System.currentTimeMillis();
            llmMs = t3 - t2;

            logger.info("RAG query: retrievalMs={}, llmMs={}, sources={}", retrievalMs, llmMs, sources);

        } else {
            // General chat — no telemetry needed
            String systemPrompt = buildPersonalizedSystemPrompt(
                    GENERAL_SYSTEM_PROMPT, req.persona(), req.language()
            );
            answer = handleGeneralChat(conv, req.message(), systemPrompt);
        }

        // 3. Save assistant reply
        Message assistantMsg = Message.createAssistantMessage(conv, answer);
        messageRepo.save(assistantMsg);

        return new ChatResponse(conv.getId(), answer, retrievalMs, llmMs, sources);
    }

    // ---------------- MODULE 3: PERSONA + LANGUAGE ----------------

    private String buildPersonalizedSystemPrompt(String basePrompt, String persona, String language) {
        StringBuilder sb = new StringBuilder(basePrompt);

        // Persona injection
        if (persona != null && !persona.isBlank() && !"Analyst".equals(persona)) {
            switch (persona) {
                case "Commercial Lead" -> sb.append(
                        "\n\nTailor your response for a Commercial Lead. " +
                        "Emphasize revenue impact, market positioning, ROI, and commercial metrics."
                );
                case "Technical Lead" -> sb.append(
                        "\n\nTailor your response for a Technical Lead. " +
                        "Emphasize architecture decisions, system performance, scalability, and technical implementation details."
                );
                case "External Merchant" -> sb.append(
                        "\n\nIMPORTANT: The reader is an External Merchant with no corporate background. " +
                        "Write at an 8th-grade reading level. Use short sentences. " +
                        "Remove ALL corporate jargon, acronyms, and technical terms. " +
                        "Explain everything in plain, everyday language."
                );
            }
        }

        // Language injection
        if ("Español".equals(language)) {
            sb.append("\n\nIMPORTANT: Generate your ENTIRE response in Spanish (Español). " +
                      "All text, bullet points, and explanations must be in Spanish.");
        }

        return sb.toString();
    }

    // ---------------- GENERAL CHAT ----------------

    private String handleGeneralChat(Conversation conv, String userMessage, String systemPrompt) {
        List<Message> history =
                messageRepo.findTop10ByConversation_IdOrderByCreatedAtDesc(conv.getId());

        StringBuilder sb = new StringBuilder();
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            String prefix = "user".equals(m.getRole()) ? "User" : "Assistant";
            sb.append(prefix).append(": ").append(m.getContent()).append("\n");
        }
        sb.append("User: ").append(userMessage);

        String fullUserMessage = sb.toString();
        if (fullUserMessage.length() > MAX_PROMPT_CHARS) {
            fullUserMessage = fullUserMessage.substring(
                    fullUserMessage.length() - MAX_PROMPT_CHARS);
        }

        return ollamaService.chat(systemPrompt, fullUserMessage);
    }

    // ---------------- CONVERSATION ----------------

    private Conversation getOrCreateConversation(UUID id, UUID userId) {
        if (id == null) {
            Conversation conv = Conversation.create(userId, GENERAL_SYSTEM_PROMPT);
            return conversationRepo.save(conv);
        }

        return conversationRepo.findByIdAndUserId(id, userId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Conversation not found or access denied"));
    }
}
