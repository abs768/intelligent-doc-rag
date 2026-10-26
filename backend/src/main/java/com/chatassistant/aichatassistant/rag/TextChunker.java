package com.chatassistant.aichatassistant.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG stage 2 — split. Breaks text into overlapping chunks on word boundaries.
 *
 * <p>A chunk closes once it reaches {@link #CHUNK_SIZE_CHARS}; the next chunk starts with
 * the last words of the previous one, about {@link #CHUNK_OVERLAP_CHARS} long, so a sentence
 * that straddles a boundary appears whole in at least one chunk.
 * Python equivalent: {@code data_loader.split_documents} (RecursiveCharacterTextSplitter).
 */
@Component
public class TextChunker {

    public static final int CHUNK_SIZE_CHARS = 1500;
    public static final int CHUNK_OVERLAP_CHARS = 200;

    /** Splits text into overlapping chunks; returns an empty list for blank input. */
    public List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        String[] words = text.trim().split("\\s+");

        List<String> currentChunk = new ArrayList<>();
        int currentLength = 0;

        for (String word : words) {
            currentChunk.add(word);
            currentLength += word.length() + 1;

            if (currentLength >= CHUNK_SIZE_CHARS) {
                chunks.add(String.join(" ", currentChunk));

                int overlapLength = 0;
                int overlapStart = currentChunk.size();
                while (overlapStart > 0 && overlapLength < CHUNK_OVERLAP_CHARS) {
                    overlapStart--;
                    overlapLength += currentChunk.get(overlapStart).length() + 1;
                }

                currentChunk = new ArrayList<>(currentChunk.subList(overlapStart, currentChunk.size()));
                currentLength = overlapLength;
            }
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(String.join(" ", currentChunk));
        }
        return chunks;
    }
}
