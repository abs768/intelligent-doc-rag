package com.chatassistant.aichatassistant.rag;

import com.chatassistant.aichatassistant.entity.Document;
import com.chatassistant.aichatassistant.repository.DocumentRepository;
import com.chatassistant.aichatassistant.service.EmbeddingService;
import com.chatassistant.aichatassistant.service.QdrantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.annotation.Async;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IngestionPipelineTest {

    private final DocumentRepository documentRepo = mock(DocumentRepository.class);
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final QdrantService qdrantService = mock(QdrantService.class);
    private IngestionPipeline pipeline;

    private final UUID userId = UUID.randomUUID();
    private Document doc;

    @BeforeEach
    void setUp() {
        pipeline = new IngestionPipeline(
                new DocumentTextExtractor(), new TextChunker(), embeddingService, qdrantService, documentRepo);
        doc = Document.create(userId, "notes.txt");
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f));
    }

    @Test
    void ingestTextEmbedsAndStoresEveryChunkInOrder() {
        UUID documentId = UUID.randomUUID();
        String text = "alpha ".repeat(600);
        int expected = new TextChunker().chunk(text).size();

        int stored = pipeline.ingestText(userId, documentId, "notes.txt", text);

        assertThat(stored).isEqualTo(expected).isGreaterThan(1);
        verify(embeddingService, times(expected)).embed(anyString());
        for (int i = 0; i < expected; i++) {
            verify(qdrantService).upsertChunk(eq(userId), eq(documentId), eq(i), any(), anyString(), eq("notes.txt"));
        }
    }

    @Test
    void asyncIngestionMarksDocumentIngestedAndDeletesTheTempFile(@TempDir Path dir) throws Exception {
        UUID documentId = UUID.randomUUID();
        File upload = Files.writeString(dir.resolve("upload"), "Quarterly revenue grew 12 percent.").toFile();
        when(documentRepo.findById(documentId)).thenReturn(Optional.of(doc));

        pipeline.ingestFileAsync(userId, documentId, "notes.txt", upload);

        assertThat(doc.getStatus()).isEqualTo(Document.Status.INGESTED);
        assertThat(doc.getChunkCount()).isEqualTo(1);
        assertThat(upload).doesNotExist();
    }

    @Test
    void asyncIngestionMarksDocumentFailedWhenNoTextCanBeExtracted(@TempDir Path dir) throws Exception {
        UUID documentId = UUID.randomUUID();
        File upload = Files.writeString(dir.resolve("upload"), "   ").toFile();
        when(documentRepo.findById(documentId)).thenReturn(Optional.of(doc));

        pipeline.ingestFileAsync(userId, documentId, "notes.txt", upload);

        assertThat(doc.getStatus()).isEqualTo(Document.Status.FAILED);
        verifyNoInteractions(qdrantService);
        assertThat(upload).doesNotExist();
    }

    @Test
    void asyncIngestionMarksDocumentFailedWhenTheVectorStoreFails(@TempDir Path dir) throws Exception {
        UUID documentId = UUID.randomUUID();
        File upload = Files.writeString(dir.resolve("upload"), "Some text to index.").toFile();
        when(documentRepo.findById(documentId)).thenReturn(Optional.of(doc));
        doThrow(new RuntimeException("qdrant down"))
                .when(qdrantService).upsertChunk(any(), any(), anyInt(), any(), anyString(), anyString());

        pipeline.ingestFileAsync(userId, documentId, "notes.txt", upload);

        assertThat(doc.getStatus()).isEqualTo(Document.Status.FAILED);
        assertThat(upload).doesNotExist();
    }

    @Test
    void asyncEntryPointIsAnnotatedForSpringsExecutor() throws Exception {
        assertThat(IngestionPipeline.class
                .getMethod("ingestFileAsync", UUID.class, UUID.class, String.class, File.class)
                .isAnnotationPresent(Async.class)).isTrue();
    }
}
