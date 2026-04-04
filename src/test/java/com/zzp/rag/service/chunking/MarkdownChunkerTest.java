package com.zzp.rag.service.chunking;

import com.zzp.rag.config.RagProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class MarkdownChunkerTest {

    @Test
    void shouldSplitLongMarkdownIntoChunks() {
        RagProperties properties = new RagProperties();
        properties.getChunking().setMaxChars(30);
        properties.getChunking().setOverlapChars(5);

        MarkdownChunker chunker = new MarkdownChunker(properties);
        String markdown = "# title\n\n"
                + "This is a long paragraph for chunking tests. "
                + "It should exceed the configured maxChars threshold by a large margin.\n\n"
                + "Second paragraph is also long enough to keep split behavior deterministic.";

        List<String> chunks = chunker.split(markdown);

        Assertions.assertFalse(chunks.isEmpty());
        Assertions.assertTrue(chunks.get(0).contains("title"));
    }
}
