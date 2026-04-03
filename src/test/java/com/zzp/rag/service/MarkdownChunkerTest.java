package com.zzp.rag.service;

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
        String markdown = "# title\n\n这是第一段内容，长度超过设定阈值，用于测试切片。\n\n这是第二段。";

        List<String> chunks = chunker.split(markdown);

        Assertions.assertFalse(chunks.isEmpty());
        Assertions.assertTrue(chunks.size() >= 2);
    }
}
