package com.chatassistant.aichatassistant.controller;

import com.chatassistant.aichatassistant.entity.Document;
import com.chatassistant.aichatassistant.entity.User;
import com.chatassistant.aichatassistant.repository.DocumentRepository;
import com.chatassistant.aichatassistant.repository.UserRepository;
import com.chatassistant.aichatassistant.service.JwtService;
import com.chatassistant.aichatassistant.service.QdrantService;
import com.chatassistant.aichatassistant.service.EmbeddingService;
import com.chatassistant.aichatassistant.service.OllamaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class DocumentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private QdrantService qdrantService;

    @MockBean
    private EmbeddingService embeddingService;

    @MockBean
    private OllamaService ollamaService;

    private String authToken;
    private User testUser;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        documentRepository.deleteAll();

        testUser = new User("test@test.com", passwordEncoder.encode("password"), "Test User");
        testUser = userRepository.save(testUser);
        authToken = jwtService.generateToken(testUser);

        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f, 0.3f));
    }

    @Test
    void uploadDocument_WithoutAuth_ReturnsUnauthorized() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", MediaType.TEXT_PLAIN_VALUE, "Test content".getBytes()
        );

        mockMvc.perform(multipart("/api/documents/upload").file(file))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadDocument_WithAuth_Success() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", MediaType.TEXT_PLAIN_VALUE,
                "This is test content for document upload.".getBytes()
        );

        mockMvc.perform(multipart("/api/documents/upload").file(file)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId", notNullValue()))
                .andExpect(jsonPath("$.filename", is("test.txt")))
                .andExpect(jsonPath("$.message", containsString("successfully")));
    }

    @Test
    void listDocuments_WithoutAuth_ReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/documents/list"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listDocuments_Empty_ReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/documents/list").header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listDocuments_WithDocuments_ReturnsDocuments() throws Exception {
        Document doc1 = Document.create(testUser.getId(), "doc1.txt");
        doc1.setStatus(Document.Status.INGESTED);
        doc1.setChunkCount(5);
        documentRepository.save(doc1);

        Document doc2 = Document.create(testUser.getId(), "doc2.txt");
        doc2.setStatus(Document.Status.INGESTED);
        doc2.setChunkCount(3);
        documentRepository.save(doc2);

        mockMvc.perform(get("/api/documents/list").header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].filename", is("doc1.txt")))
                .andExpect(jsonPath("$[0].chunkCount", is(5)));
    }

    @Test
    void deleteDocument_WithoutAuth_ReturnsUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/documents/" + java.util.UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteDocument_Success() throws Exception {
        Document doc = Document.create(testUser.getId(), "test.txt");
        doc.setStatus(Document.Status.INGESTED);
        doc = documentRepository.save(doc);

        mockMvc.perform(delete("/api/documents/" + doc.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("deleted")));
    }

    @Test
    void deleteAllDocuments_Success() throws Exception {
        Document doc1 = Document.create(testUser.getId(), "doc1.txt");
        documentRepository.save(doc1);

        mockMvc.perform(delete("/api/documents/all").header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("deleted")));
    }
}
