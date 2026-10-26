package com.chatassistant.aichatassistant.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTest {

    private final TextChunker chunker = new TextChunker();

    private static String words(int count) {
        return IntStream.range(0, count).mapToObj(i -> "word" + i).collect(Collectors.joining(" "));
    }

    @Test
    void blankTextProducesNoChunks() {
        assertThat(chunker.chunk(null)).isEmpty();
        assertThat(chunker.chunk("   \n\t ")).isEmpty();
    }

    @Test
    void shortTextIsOneChunkWithWhitespaceNormalised() {
        assertThat(chunker.chunk("  Hello   world \n again ")).containsExactly("Hello world again");
    }

    @Test
    void longTextSplitsIntoChunksNearTheTargetSize() {
        List<String> chunks = chunker.chunk(words(1000));

        assertThat(chunks).hasSizeGreaterThan(1);
        for (String chunk : chunks.subList(0, chunks.size() - 1)) {
            assertThat(chunk.length() + 1).isGreaterThanOrEqualTo(TextChunker.CHUNK_SIZE_CHARS);
            assertThat(chunk.length()).isLessThan(TextChunker.CHUNK_SIZE_CHARS + 20);
        }
    }

    @Test
    void consecutiveChunksOverlapByAboutTheConfiguredAmount() {
        List<String> chunks = chunker.chunk(words(1000));

        for (int i = 0; i + 1 < chunks.size(); i++) {
            String previous = chunks.get(i);
            String next = chunks.get(i + 1);
            String tail = previous.substring(previous.length() - TextChunker.CHUNK_OVERLAP_CHARS + 20);
            assertThat(next).contains(tail);
        }
    }

    @Test
    void everyWordAppearsInSomeChunk() {
        String text = words(800);
        List<String> chunks = chunker.chunk(text);
        String joined = String.join(" ", chunks);

        for (String word : text.split(" ")) {
            assertThat(joined).contains(word);
        }
    }
}
