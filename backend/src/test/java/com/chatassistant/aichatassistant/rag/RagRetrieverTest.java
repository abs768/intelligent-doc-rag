package com.chatassistant.aichatassistant.rag;

import com.chatassistant.aichatassistant.dto.RetrievedChunk;
import com.chatassistant.aichatassistant.entity.Document;
import com.chatassistant.aichatassistant.repository.DocumentRepository;
import com.chatassistant.aichatassistant.service.EmbeddingService;
import com.chatassistant.aichatassistant.service.QdrantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagRetrieverTest {

    @Mock private DocumentRepository documentRepo;
    @Mock private EmbeddingService embeddingService;
    @Mock private QdrantService qdrantService;

    @InjectMocks private RagRetriever retriever;

    private final UUID userId = UUID.randomUUID();
    private final UUID docId = UUID.randomUUID();

    @Test
    void blankQueryReturnsNothingWithoutEmbeddingOrSearching() {
        assertThat(retriever.findRelevantChunks(userId, "  ", 5, null)).isEmpty();
        assertThat(retriever.findRelevantChunksForCitations(userId, null, 5, null)).isEmpty();
        assertThat(retriever.findRelevantChunksWithSources(userId, "", 5, null).chunks()).isEmpty();

        verifyNoInteractions(embeddingService, qdrantService);
    }

    @Test
    void documentOwnedBySomeoneElseIsRejectedBeforeSearching() {
        when(documentRepo.findByIdAndUserId(docId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> retriever.findRelevantChunks(userId, "question", 5, List.of(docId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Access denied");

        verifyNoInteractions(embeddingService, qdrantService);
    }

    @Test
    void ownedDocumentIsSearchedWithTheQueryVectorAndUserFilter() {
        List<Float> vector = List.of(0.1f, 0.2f);
        RetrievedChunk chunk = new RetrievedChunk(docId, "notes.txt", 0, "chunk text", 0.9f);
        when(documentRepo.findByIdAndUserId(docId, userId)).thenReturn(Optional.of(mock(Document.class)));
        when(embeddingService.embed("question")).thenReturn(vector);
        when(qdrantService.searchWithCitations(userId, vector, 5, List.of(docId))).thenReturn(List.of(chunk));

        List<RetrievedChunk> result = retriever.findRelevantChunksForCitations(userId, "question", 5, List.of(docId));

        assertThat(result).containsExactly(chunk);
        verify(qdrantService).searchWithCitations(userId, vector, 5, List.of(docId));
    }

    @Test
    void noSelectedDocumentsSearchesAllOfTheUsersDocuments() {
        List<Float> vector = List.of(0.3f);
        when(embeddingService.embed("question")).thenReturn(vector);
        when(qdrantService.search(eq(userId), eq(vector), anyInt(), any())).thenReturn(List.of("chunk text"));

        assertThat(retriever.findRelevantChunks(userId, "question", 3, null)).containsExactly("chunk text");
        verifyNoInteractions(documentRepo);
    }
}
