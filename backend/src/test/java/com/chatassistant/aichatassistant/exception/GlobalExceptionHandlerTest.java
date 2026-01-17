package com.chatassistant.aichatassistant.exception;

import com.chatassistant.aichatassistant.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @InjectMocks
    private GlobalExceptionHandler exceptionHandler;

    @Mock
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        when(request.getRequestURI()).thenReturn("/api/test");
    }

    @Test
    void handleBadRequest_ReturnsBadRequest() {
        BadRequestException ex = new BadRequestException("Invalid input");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleBadRequest(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Invalid input", response.getBody().message());
        assertEquals(400, response.getBody().status());
    }

    @Test
    void handleNotFound_ReturnsNotFound() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Resource not found");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleNotFound(ex, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Resource not found", response.getBody().message());
        assertEquals(404, response.getBody().status());
    }

    @Test
    void handleServiceUnavailable_ReturnsServiceUnavailable() {
        ServiceUnavailableException ex = new ServiceUnavailableException("Service down");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleServiceUnavailable(ex, request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("Service down", response.getBody().message());
        assertEquals(503, response.getBody().status());
    }

    @Test
    void handleIllegalArgument_ReturnsBadRequest() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid argument");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleIllegalArgument(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid argument", response.getBody().message());
    }

    @Test
    void handleAuthenticationException_ReturnsUnauthorized() {
        BadCredentialsException ex = new BadCredentialsException("Bad credentials");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleAuthenticationException(ex, request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("Invalid credentials", response.getBody().message());
        assertEquals(401, response.getBody().status());
    }

    @Test
    void handleMaxUploadSizeExceeded_ReturnsPayloadTooLarge() {
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(1000000);

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleMaxUploadSizeExceeded(ex, request);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals(413, response.getBody().status());
        assertTrue(response.getBody().message().contains("size exceeds"));
    }

    @Test
    void handleGenericException_ReturnsInternalServerError() {
        Exception ex = new RuntimeException("Unexpected error");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleGenericException(ex, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(500, response.getBody().status());
        assertTrue(response.getBody().message().contains("unexpected error"));
    }
}
