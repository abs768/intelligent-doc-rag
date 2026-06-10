package com.chatassistant.aichatassistant;

import com.chatassistant.aichatassistant.dto.ChatRequest;
import com.chatassistant.aichatassistant.dto.ChatResponse;
import com.chatassistant.aichatassistant.dto.GroundedAnswer;
import com.chatassistant.aichatassistant.service.ChatService;
import com.chatassistant.aichatassistant.service.DocumentService;
import com.chatassistant.aichatassistant.service.EmbeddingService;
import com.chatassistant.aichatassistant.service.OllamaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the grounded RAG path.
 *
 * Stack under test:
 *   - real Postgres        (Testcontainers)
 *   - real Qdrant          (Testcontainers)
 *   - real DocumentService (PDF/text extraction + chunking + embedding + upsert)
 *   - real QdrantService   (vector search, payload filtering)
 *   - real ChatService     (grounded path: numbered context, citation mapping,
 *                           empty-retrieval refusal, no-valid-citation refusal)
 *   - stub EmbeddingService (deterministic SHA-256 vectors — no Ollama needed)
 *   - stub OllamaService    (canned GroundedAnswer per test; counts invocations)
 *
 * What this test PROVES that the unit tests cannot:
 *   1. A citation returned by chatGrounded actually maps to a chunk that was
 *      ingested and upserted to Qdrant in this run — not a value the LLM made up.
 *   2. The empty-retrieval refusal path never reaches the LLM, even when the
 *      whole Spring/Qdrant/Postgres stack is real.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class GroundedRagIntegrationTest {

    // ======================== TESTCONTAINERS ========================

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> qdrant = new GenericContainer<>("qdrant/qdrant:latest")
            .withExposedPorts(6334);

    @DynamicPropertySource
    static void wireSpringProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");

        registry.add("qdrant.host", qdrant::getHost);
        registry.add("qdrant.port", () -> qdrant.getMappedPort(6334));
        registry.add("qdrant.collection", () -> "grounded-rag-test");
    }

    // ======================== TEST INFRA ========================

    @TestConfiguration
    static class TestInfraConfig {

        @Bean
        @Primary
        public QdrantClient testQdrantClient() throws Exception {
            QdrantClient client = new QdrantClient(
                    QdrantGrpcClient.newBuilder(
                            qdrant.getHost(),
                            qdrant.getMappedPort(6334),
                            false
                    ).build()
            );
            if (!client.collectionExistsAsync("grounded-rag-test").get()) {
                client.createCollectionAsync(
                        "grounded-rag-test",
                        Collections.VectorParams.newBuilder()
                                .setSize(768)
                                .setDistance(Collections.Distance.Cosine)
                                .build()
                ).get();
            }
            return client;
        }

        @Bean
        @Primary
        public EmbeddingService deterministicEmbeddingService() {
            return new DeterministicEmbeddingService();
        }

        @Bean
        @Primary
        public StubOllamaService stubOllamaService() {
            return new StubOllamaService();
        }
    }

    /** Same approach as the existing MultiTenant test — SHA-256 → 768-float vector. */
    static class DeterministicEmbeddingService extends EmbeddingService {
        @Override
        public List<Float> embed(String text) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256")
                        .digest(text.toLowerCase().getBytes(StandardCharsets.UTF_8));
                Random rng = new Random(java.nio.ByteBuffer.wrap(hash).getLong());
                List<Float> vector = new ArrayList<>(768);
                for (int i = 0; i < 768; i++) vector.add(rng.nextFloat());
                return vector;
            } catch (Exception e) {
                throw new RuntimeException("Embedding generation failed", e);
            }
        }
    }

    /**
     * Replaces the production OllamaService. The chatStructured() call returns
     * whatever {@code nextAnswer} is set to, and increments {@code callCount} so
     * tests can assert the LLM was (or wasn't) invoked.
     */
    static class StubOllamaService extends OllamaService {
        final AtomicInteger callCount = new AtomicInteger(0);
        volatile GroundedAnswer nextAnswer;

        StubOllamaService() {
            super("http://localhost:0", "stub-model", new ObjectMapper(),
                    Validation.buildDefaultValidatorFactory().getValidator());
        }

        void reset() {
            callCount.set(0);
            nextAnswer = null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T chatStructured(String systemPrompt, String userMessage, Class<T> type) {
            callCount.incrementAndGet();
            if (nextAnswer == null) {
                throw new IllegalStateException(
                        "StubOllamaService.chatStructured invoked but nextAnswer not set — "
                                + "this is unexpected for the test under run.");
            }
            return (T) nextAnswer;
        }
    }

    // ======================== FIXTURES ========================

    @Autowired private DocumentService documentService;
    @Autowired private ChatService chatService;
    @Autowired private StubOllamaService ollamaStub;

    private static final UUID TENANT_WITH_DOC  = UUID.randomUUID();
    private static final UUID TENANT_WITHOUT_DOC = UUID.randomUUID();

    @BeforeEach
    void resetStub() {
        ollamaStub.reset();
    }

    private static ChatRequest request(String message, List<String> docIds) {
        return new ChatRequest(null, message, docIds, true, "Analyst", "English");
    }

    // ======================== TESTS ========================

    @Test
    void grounded_returnsCitationLinkedToActualIngestedChunk() {
        // GIVEN: a tiny document is ingested for TENANT_WITH_DOC. The chunker will
        //        produce exactly one chunk because the text is well under the 1500-char
        //        threshold. We capture the documentId for cross-checking.
        String content = "Acme Corp Q3 2024 revenue was $4.2M, up 18 percent year over year.";
        MockMultipartFile file = new MockMultipartFile(
                "file", "acme-q3.txt", "text/plain", content.getBytes(StandardCharsets.UTF_8));
        UUID docId = documentService.ingestDocument(TENANT_WITH_DOC, file);

        // AND: the LLM stub is programmed to claim the answer is supported by chunk 1.
        ollamaStub.nextAnswer = new GroundedAnswer(
                "Q3 2024 revenue was $4.2M.", List.of(1));

        // WHEN: a grounded query is issued by TENANT_WITH_DOC against this document.
        ChatResponse resp = chatService.chatGrounded(
                TENANT_WITH_DOC,
                request("What was Q3 2024 revenue?", List.of(docId.toString())));

        // THEN: the LLM was called exactly once and the response carries a citation
        //       whose documentId equals the doc we just ingested. The citation
        //       cannot have come from anywhere other than the real Qdrant chunk —
        //       the chunkIndex and filename come from the chunk payload, not from
        //       the LLM stub's GroundedAnswer.
        assertThat(ollamaStub.callCount.get()).isEqualTo(1);
        assertThat(resp.response()).isEqualTo("Q3 2024 revenue was $4.2M.");
        assertThat(resp.citations()).hasSize(1);
        assertThat(resp.citations().get(0).documentId()).isEqualTo(docId);
        assertThat(resp.citations().get(0).filename()).isEqualTo("acme-q3.txt");
        assertThat(resp.citations().get(0).chunkIndex()).isEqualTo(0);
        assertThat(resp.citations().get(0).snippet()).contains("$4.2M");
        assertThat(resp.sources()).containsExactly("acme-q3.txt");
    }

    @Test
    void grounded_refusesAndNeverCallsLLM_whenTenantHasNoDocuments() {
        // GIVEN: TENANT_WITHOUT_DOC has ingested nothing. The userId MUST filter in
        //        QdrantService guarantees they see no chunks from any other tenant.

        // WHEN: they issue a grounded query.
        ChatResponse resp = chatService.chatGrounded(
                TENANT_WITHOUT_DOC,
                request("anything goes here", null));

        // THEN: canned refusal, empty citations, and the LLM stub was NOT invoked.
        assertThat(resp.response()).isEqualTo(ChatService.GROUNDED_REFUSAL);
        assertThat(resp.citations()).isEmpty();
        assertThat(resp.sources()).isEmpty();
        assertThat(ollamaStub.callCount.get())
                .as("Empty-retrieval path MUST short-circuit before reaching the LLM")
                .isZero();
    }

    @Test
    void grounded_filtersOutHallucinatedCitationIndices_andForcesRefusal() {
        // GIVEN: a single-chunk document is ingested. Only citation index 1 is valid.
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.txt", "text/plain",
                "Only one short chunk in this document.".getBytes(StandardCharsets.UTF_8));
        UUID docId = documentService.ingestDocument(TENANT_WITH_DOC, file);

        // AND: the LLM stub invents a citation to chunk 99, which doesn't exist.
        ollamaStub.nextAnswer = new GroundedAnswer(
                "Some specific claim with no support.", List.of(99));

        // WHEN: a grounded query is issued.
        ChatResponse resp = chatService.chatGrounded(
                TENANT_WITH_DOC,
                request("does anything", List.of(docId.toString())));

        // THEN: the hallucinated index is filtered out, so no valid citation survives,
        //       so the answer is overwritten with the canned refusal. The LLM was
        //       still called (we can't know it would lie without calling it once),
        //       but the response is now safe.
        assertThat(ollamaStub.callCount.get()).isEqualTo(1);
        assertThat(resp.response()).isEqualTo(ChatService.GROUNDED_REFUSAL);
        assertThat(resp.citations()).isEmpty();
    }
}
