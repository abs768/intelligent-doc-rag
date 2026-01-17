package com.chatassistant.aichatassistant.dto;

public record RegisterRequest(
        String email,
        String password,
        String name
) {
}