package com.chatassistant.aichatassistant.controller;

import com.chatassistant.aichatassistant.dto.LoginRequest;
import com.chatassistant.aichatassistant.dto.RegisterRequest;
import com.chatassistant.aichatassistant.entity.User;
import com.chatassistant.aichatassistant.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.chatassistant.aichatassistant.service.QdrantService;
import com.chatassistant.aichatassistant.service.OllamaService;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private QdrantService qdrantService;

    @MockBean
    private OllamaService ollamaService;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void register_Success() throws Exception {
        // Arrange
        RegisterRequest request = new RegisterRequest(
                "newuser@test.com",
                "password123",
                "New User"
        );

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.email", is("newuser@test.com")))
                .andExpect(jsonPath("$.name", is("New User")));
    }

    @Test
    void register_DuplicateEmail_ReturnsBadRequest() throws Exception {
        // Arrange - Create existing user
        User existingUser = new User("existing@test.com", passwordEncoder.encode("password"), "Existing");
        userRepository.save(existingUser);

        RegisterRequest request = new RegisterRequest(
                "existing@test.com",
                "password123",
                "New User"
        );

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_Success() throws Exception {
        // Arrange - Create user
        User user = new User("test@test.com", passwordEncoder.encode("password123"), "Test User");
        userRepository.save(user);

        LoginRequest request = new LoginRequest("test@test.com", "password123");

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.email", is("test@test.com")))
                .andExpect(jsonPath("$.name", is("Test User")));
    }

    @Test
    void login_InvalidCredentials_ReturnsBadRequest() throws Exception {
        // Arrange
        LoginRequest request = new LoginRequest("nonexistent@test.com", "wrongpassword");

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_WrongPassword_ReturnsBadRequest() throws Exception {
        // Arrange - Create user
        User user = new User("test@test.com", passwordEncoder.encode("correctpassword"), "Test User");
        userRepository.save(user);

        LoginRequest request = new LoginRequest("test@test.com", "wrongpassword");

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}