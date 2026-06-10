package com.chatassistant.aichatassistant.service;

import com.chatassistant.aichatassistant.dto.ChatRequest;
import com.chatassistant.aichatassistant.dto.ChatResponse;
import com.chatassistant.aichatassistant.dto.Citation;
import com.chatassistant.aichatassistant.dto.GroundedAnswer;
import com.chatassistant.aichatassistant.dto.RetrievedChunk;
import com.chatassistant.aichatassistant.entity.Conversation;
import com.chatassistant.aichatassistant.entity.Message;
import com.chatassistant.aichatassistant.repository.ConversationRepository;
import com.chatassistant.aichatassistant.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceGroundedTest {

    @Mock private ConversationRepository conversationRepo;
    @Mock private MessageRepository messageRepo;
    @Mock private OllamaService ollamaService;
    @Mock private DocumentService documentService;

    @InjectMocks private ChatService chatService;

    private UUID userId;
    private UUID conversationId;
    private UUID docId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        conversationId = UUID.randomUUID();
        docId = UUID.randomUUID();

        // Conversation entity has no setId — JPA assigns it on save. Mock it so we
        // can return a known id without reflection or production-code changes.
        Conversation conv = mock(Conversation.class);
        lenient().when(conv.getId()).thenReturn(conversationId);

        lenient().when(conversationRepo.findByIdAndUserId(conversationId, userId))
                .thenReturn(Optional.of(conv));
        lenient().when(conversationRepo.save(any(Conversation.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(messageRepo.save(any(Message.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private ChatRequest request(String message) {
        return new ChatRequest(conversationId, message, List.of(docId.toString()), true, "Analyst", "English");
    }

    @Test
    void grounded_returnsCitationsPointingAtRealRetrievedChunks() {
        // Two chunks retrieved from documentService.
        RetrievedChunk c1 = new RetrievedChunk(docId, "report.pdf", 4,
                "Q3 2024 revenue was $4.2M, up 18% YoY.", 0.93f);
        RetrievedChunk c2 = new RetrievedChunk(docId, "report.pdf", 5,
                "Operating margin was 22%; headcount grew to 134.", 0.88f);
        when(documentService.findRelevantChunksForCitations(eq(userId), anyString(), anyInt(), any()))
                .thenReturn(List.of(c1, c2));

        // Model returns a structured answer citing chunk 1 (1-based).
        when(ollamaService.chatStructured(anyString(), anyString(), eq(GroundedAnswer.class)))
                .thenReturn(new GroundedAnswer("Q3 2024 revenue was $4.2M.", List.of(1)));

        ChatResponse resp = chatService.chatGrounded(userId, request("What was Q3 revenue?"));

        assertEquals("Q3 2024 revenue was $4.2M.", resp.response());
        assertNotNull(resp.citations());
        assertEquals(1, resp.citations().size());

        Citation cite = resp.citations().get(0);
        assertEquals(docId, cite.documentId());
        assertEquals("report.pdf", cite.filename());
        assertEquals(4, cite.chunkIndex());           // chunkIndex from c1, not the LLM-supplied index
        assertTrue(cite.snippet().contains("4.2M"));   // snippet sourced from real chunk content

        assertEquals(List.of("report.pdf"), resp.sources());
        verify(ollamaService).chatStructured(anyString(), anyString(), eq(GroundedAnswer.class));
    }

    @Test
    void grounded_outOfRangeCitationIndicesAreFilteredOut() {
        RetrievedChunk only = new RetrievedChunk(docId, "doc.pdf", 0,
                "Some content", 0.9f);
        when(documentService.findRelevantChunksForCitations(eq(userId), anyString(), anyInt(), any()))
                .thenReturn(List.of(only));

        // Model invents index 7 (only 1 chunk was provided) — must be filtered.
        when(ollamaService.chatStructured(anyString(), anyString(), eq(GroundedAnswer.class)))
                .thenReturn(new GroundedAnswer("Some claim", List.of(7)));

        ChatResponse resp = chatService.chatGrounded(userId, request("anything"));

        // No valid citations survived → forced refusal.
        assertEquals(ChatService.GROUNDED_REFUSAL, resp.response());
        assertTrue(resp.citations().isEmpty());
        assertTrue(resp.sources().isEmpty());
    }

    @Test
    void grounded_refusesWithoutCallingLLM_whenNoChunksRetrieved() {
        when(documentService.findRelevantChunksForCitations(eq(userId), anyString(), anyInt(), any()))
                .thenReturn(List.of());

        ChatResponse resp = chatService.chatGrounded(userId, request("something not in any doc"));

        assertEquals(ChatService.GROUNDED_REFUSAL, resp.response());
        assertNotNull(resp.citations());
        assertTrue(resp.citations().isEmpty());
        assertEquals(0L, resp.llmInferenceLatencyMs(), "LLM must not be invoked");

        // Critical: no structured LLM call was made.
        verify(ollamaService, never()).chatStructured(anyString(), anyString(), any());
    }

    @Test
    void grounded_persistsBothUserMessageAndAssistantReply() {
        when(documentService.findRelevantChunksForCitations(eq(userId), anyString(), anyInt(), any()))
                .thenReturn(List.of());

        chatService.chatGrounded(userId, request("anything"));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepo, times(2)).save(captor.capture());
        List<Message> saved = captor.getAllValues();
        assertEquals(Message.Role.USER, saved.get(0).getRole());
        assertEquals(Message.Role.ASSISTANT, saved.get(1).getRole());
        assertEquals(ChatService.GROUNDED_REFUSAL, saved.get(1).getContent());
    }

    @Test
    void grounded_emptyMessage_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                chatService.chatGrounded(userId, request("")));
        verify(documentService, never()).findRelevantChunksForCitations(any(), any(), anyInt(), any());
    }
}
