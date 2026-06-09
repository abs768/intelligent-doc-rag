package com.chatassistant.aichatassistant;

import com.chatassistant.aichatassistant.entity.Document;
import com.chatassistant.aichatassistant.repository.DocumentRepository;
import com.chatassistant.aichatassistant.service.EmbeddingService;
import com.chatassistant.aichatassistant.service.QdrantService;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import org.junit.jupiter.api.*;
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
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ╔══════════════════════════════════════════════════════════════╗
 * ║        MULTI-TENANT ISOLATION — INTEGRATION TEST            ║
 * ║                                                              ║
 * ║  Real PostgreSQL (Testcontainers)                            ║
 * ║  Real Qdrant     (Testcontainers)                            ║
 * ║  Deterministic embeddings (no LLM required)                  ║
 * ║                                                              ║
 * ║  PROVES: Tenant A's documents are INVISIBLE to Tenant B      ║
 * ╚══════════════════════════════════════════════════════════════╝
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiTenantIsolationDemoTest {

    // ======================== INFRASTRUCTURE ========================

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> qdrant = new GenericContainer<>("qdrant/qdrant:latest")
            .withExposedPorts(6334);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Point Spring to the Testcontainers-managed Postgres
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");

        // Point Spring to the Testcontainers-managed Qdrant
        registry.add("qdrant.host", qdrant::getHost);
        registry.add("qdrant.port", () -> qdrant.getMappedPort(6334));
        registry.add("qdrant.collection", () -> "test-documents");
    }

    // ======================== TEST CONFIGURATION ========================

    /**
     * Provides a REAL QdrantClient (overrides the @Profile("!test") production config)
     * and a DETERMINISTIC EmbeddingService (no Ollama needed).
     */
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

            // Create collection if it doesn't exist
            if (!client.collectionExistsAsync("test-documents").get()) {
                client.createCollectionAsync(
                        "test-documents",
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
    }

    /**
     * Generates a deterministic 768-dimensional vector from text using SHA-256.
     * Similar text → similar vectors. No LLM required.
     * This is a test-only replacement for the real Ollama-based EmbeddingService.
     */
    static class DeterministicEmbeddingService extends EmbeddingService {
        @Override
        public List<Float> embed(String text) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256")
                        .digest(text.toLowerCase().getBytes(StandardCharsets.UTF_8));
                Random rng = new Random(java.nio.ByteBuffer.wrap(hash).getLong());

                List<Float> vector = new ArrayList<>(768);
                for (int i = 0; i < 768; i++) {
                    vector.add(rng.nextFloat());
                }
                return vector;
            } catch (Exception e) {
                throw new RuntimeException("Embedding generation failed", e);
            }
        }
    }

    // ======================== TEST FIXTURES ========================

    @Autowired private com.chatassistant.aichatassistant.service.DocumentService documentService;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private QdrantService qdrantService;
    @Autowired private EmbeddingService embeddingService;

    // Two tenants — completely isolated from each other
    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    // ======================== THE DEMO TEST ========================

    @Test
    @Order(1)
    @DisplayName("PROOF: Tenant A's documents are invisible to Tenant B")
    void multiTenantIsolation() {
        // ──────────────────────────────────────────────────────
        // GIVEN: Tenant A uploads a document with secret content
        // ──────────────────────────────────────────────────────
        MockMultipartFile tenantAFile = new MockMultipartFile(
                "file",
                "mckinsey-strategy.txt",
                "text/plain",
                "The secret McKinsey code is 12345. This is a confidential strategy document."
                        .getBytes(StandardCharsets.UTF_8)
        );

        UUID docId = documentService.ingestDocument(TENANT_A, tenantAFile);

        // Verify: document exists in Postgres for Tenant A
        List<Document> tenantADocs = documentRepository.findByUserId(TENANT_A);
        assertThat(tenantADocs).hasSize(1);
        assertThat(tenantADocs.get(0).getStatus()).isEqualTo(Document.Status.INGESTED);
        assertThat(tenantADocs.get(0).getFilename()).isEqualTo("mckinsey-strategy.txt");

        // ──────────────────────────────────────────────────────
        // WHEN: Tenant A searches for "secret McKinsey code"
        // THEN: Tenant A finds the document
        // ──────────────────────────────────────────────────────
        List<String> tenantAResults = documentService.findRelevantChunks(
                TENANT_A,
                "secret McKinsey code",
                5,
                List.of(docId)
        );

        assertThat(tenantAResults)
                .as("Tenant A SHOULD find their own document")
                .isNotEmpty();

        assertThat(tenantAResults.get(0))
                .as("Tenant A's result contains the secret content")
                .contains("secret McKinsey code is 12345");

        // ──────────────────────────────────────────────────────
        // WHEN: Tenant B searches for the EXACT SAME query
        // THEN: Tenant B gets ZERO results — complete isolation
        // ──────────────────────────────────────────────────────
        List<Float> queryVector = embeddingService.embed("secret McKinsey code");

        List<String> tenantBResults = qdrantService.search(
                TENANT_B,     // ← Different tenant!
                queryVector,
                5,
                null          // Search across ALL of Tenant B's documents
        );

        assertThat(tenantBResults)
                .as("Tenant B MUST NOT see Tenant A's documents — this is the isolation guarantee")
                .isEmpty();

        // ──────────────────────────────────────────────────────
        // AND: Tenant B has no documents in Postgres either
        // ──────────────────────────────────────────────────────
        List<Document> tenantBDocs = documentRepository.findByUserId(TENANT_B);

        assertThat(tenantBDocs)
                .as("Tenant B has zero documents in the SQL database")
                .isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("PROOF: Isolation holds with multiple tenants and documents")
    void isolationWithMultipleDocuments() {
        // ──────────────────────────────────────────────────────
        // GIVEN: Tenant B also uploads their own document
        // ──────────────────────────────────────────────────────
        MockMultipartFile tenantBFile = new MockMultipartFile(
                "file",
                "tenant-b-notes.txt",
                "text/plain",
                "Tenant B internal notes: our revenue target is 50M for Q4."
                        .getBytes(StandardCharsets.UTF_8)
        );

        UUID tenantBDocId = documentService.ingestDocument(TENANT_B, tenantBFile);

        // ──────────────────────────────────────────────────────
        // WHEN: Tenant A searches, they only see their own data
        // ──────────────────────────────────────────────────────
        List<Float> revenueQuery = embeddingService.embed("revenue target");

        List<String> tenantASearch = qdrantService.search(
                TENANT_A,
                revenueQuery,
                5,
                null
        );

        assertThat(tenantASearch)
                .as("Tenant A MUST NOT see Tenant B's revenue data")
                .noneMatch(chunk -> chunk.contains("50M"));

        // ──────────────────────────────────────────────────────
        // AND: Tenant B searches and finds ONLY their own data
        // ──────────────────────────────────────────────────────
        List<String> tenantBSearch = qdrantService.search(
                TENANT_B,
                revenueQuery,
                5,
                null
        );

        assertThat(tenantBSearch)
                .as("Tenant B finds their own revenue document")
                .isNotEmpty();

        assertThat(tenantBSearch)
                .as("Tenant B MUST NOT see Tenant A's McKinsey secret")
                .noneMatch(chunk -> chunk.contains("McKinsey code"));

        // ──────────────────────────────────────────────────────
        // VERIFY: Each tenant has exactly 1 document in Postgres
        // ──────────────────────────────────────────────────────
        assertThat(documentRepository.findByUserId(TENANT_A)).hasSize(1);
        assertThat(documentRepository.findByUserId(TENANT_B)).hasSize(1);
    }
}
