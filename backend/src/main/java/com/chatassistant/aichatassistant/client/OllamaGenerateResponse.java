package com.chatassistant.aichatassistant.client;

import java.util.List;

public record OllamaGenerateResponse(
        String response,
        boolean done,
        List<Integer> context
) {}
