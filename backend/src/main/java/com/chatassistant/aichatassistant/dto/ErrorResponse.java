package com.chatassistant.aichatassistant.dto;

import java.time.Instant;

public record ErrorResponse(
        String message,
        String error,
        int status,
        Instant timestamp,
        String path
) {
    public ErrorResponse(String message, String error, int status, String path) {
        this(message, error, status, Instant.now(), path);
    }
}