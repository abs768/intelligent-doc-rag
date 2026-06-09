package com.chatassistant.aichatassistant.bench;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One benchmark prompt loaded from prompts.yaml.
 *
 * Category drives how the output is scored:
 *   factual / rag → output must contain every string in {@code goldContains} (case-insensitive)
 *   json          → output must parse as JSON and contain every key in {@code requiredKeys}
 */
public record Prompt(
        String id,
        String category,
        String system,
        String user,
        @JsonProperty("gold_contains") List<String> goldContains,
        @JsonProperty("required_keys") List<String> requiredKeys
) {
    public Prompt {
        goldContains = goldContains == null ? List.of() : goldContains;
        requiredKeys = requiredKeys == null ? List.of() : requiredKeys;
    }
}
