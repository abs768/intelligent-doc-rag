package com.chatassistant.aichatassistant.bench;

/**
 * One (model, prompt, iteration) measurement.
 *
 * Timings come from Ollama's response payload (nanoseconds) so they reflect the model's
 * own view of where time was spent, not just wall-clock at the client. {@code ttftMs}
 * approximates time-to-first-token as load + prompt-prefill duration; the streaming
 * endpoint would give a more precise number but at significant code cost.
 */
public record BenchResult(
        String model,
        String promptId,
        String category,
        int iteration,
        long latencyMs,
        long ttftMs,
        double tokensPerSec,
        int evalCount,
        boolean correct,
        String output,
        String errorMessage
) {
    public static BenchResult error(String model, Prompt p, int iter, String err) {
        return new BenchResult(model, p.id(), p.category(), iter, 0L, 0L, 0.0, 0, false, "", err);
    }
}
