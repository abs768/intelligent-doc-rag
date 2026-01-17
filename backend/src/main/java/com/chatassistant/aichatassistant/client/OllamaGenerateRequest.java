package com.chatassistant.aichatassistant.client;

import java.util.Map;

public record OllamaGenerateRequest(
        String model,
        String prompt,
        boolean stream,
        Map<String, Object> options
) {}
