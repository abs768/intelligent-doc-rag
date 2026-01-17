package com.chatassistant.aichatassistant.dto;

public record AuthResponse(
        String token,
        String email,
        String name
) {
}