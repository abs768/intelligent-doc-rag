package com.chatassistant.aichatassistant.dto;

import java.util.UUID;

/**
 * Public-facing citation attached to a grounded answer.
 *
 * Every field except {@code page} is sourced directly from the Qdrant chunk payload —
 * a citation can only point at a chunk that was actually retrieved for this query,
 * which is what makes the answer verifiable. The frontend uses {@code snippet} for
 * inline previews and {@code documentId} + {@code chunkIndex} to deep-link.
 */
public record Citation(
        UUID documentId,
        String filename,
        int chunkIndex,
        Integer page,
        String snippet
) {
    private static final int SNIPPET_MAX = 220;

    public static Citation fromChunk(RetrievedChunk c) {
        String content = c.content() == null ? "" : c.content();
        String snippet = content.length() > SNIPPET_MAX
                ? content.substring(0, SNIPPET_MAX) + "…"
                : content;
        return new Citation(c.documentId(), c.filename(), c.chunkIndex(), c.page(), snippet);
    }
}
