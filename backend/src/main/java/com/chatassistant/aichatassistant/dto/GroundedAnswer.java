package com.chatassistant.aichatassistant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Structured output expected from the grounded-RAG LLM call.
 *
 * The model must return both an answer string and the list of 1-based chunk numbers
 * it relied on. An empty {@code citationIndices} list is allowed ONLY when the answer
 * is a refusal — the controller-layer mapping in ChatService is what enforces that.
 *
 * No confidence field. LLM self-reported confidence is uncalibrated and would lend
 * false weight to a hallucinated answer.
 */
public record GroundedAnswer(
        @NotBlank String answer,
        @NotNull @JsonProperty("citation_indices") List<Integer> citationIndices
) {}
