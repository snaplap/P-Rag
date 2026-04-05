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

        List<Chunk> chunks = chunker.split(markdown);

        Assertions.assertFalse(chunks.isEmpty());
        Assertions.assertFalse(chunks.get(0).titlePath().isBlank());
        Assertions.assertEquals(0, chunks.get(0).chunkIndex());
    }

    @Test
    void shouldBuildHeadingPathWithThreeLevels() {
        RagProperties properties = new RagProperties();
        properties.getChunking().setMaxChars(220);
        properties.getChunking().setOverlapChars(20);

        MarkdownChunker chunker = new MarkdownChunker(properties);
        String markdown = "# 用户系统\n\n"
                + "登录概述。\n\n"
                + "## 登录功能\n\n"
                + "账户密码登录。\n\n"
                + "### 异常处理\n\n"
                + "密码错误提示。";

        List<Chunk> chunks = chunker.split(markdown);

        Assertions.assertTrue(chunks.stream().anyMatch(c -> "用户系统".equals(c.titlePath())));
        Assertions.assertTrue(chunks.stream().anyMatch(c -> "用户系统 > 登录功能".equals(c.titlePath())));
        Assertions.assertTrue(chunks.stream().anyMatch(c -> "用户系统 > 登录功能 > 异常处理".equals(c.titlePath())));
    }

    @Test
    void shouldKeepFencedCodeBlockAsAtomicChunk() {
        RagProperties properties = new RagProperties();
        properties.getChunking().setMaxChars(30);
        properties.getChunking().setOverlapChars(5);

        MarkdownChunker chunker = new MarkdownChunker(properties);
        String markdown = "# 用户系统\n\n"
                + "说明文本。\n\n"
                + "```java\n"
                + "public class Demo {\n"
                + "    void run() {\n"
                + "        System.out.println(\"hello\");\n"
                + "    }\n"
                + "}\n"
                + "```\n\n"
                + "收尾说明。";

        List<Chunk> chunks = chunker.split(markdown);

        Chunk codeChunk = chunks.stream()
                .filter(c -> c.content().startsWith("```java"))
                .findFirst()
                .orElseThrow();
        Assertions.assertTrue(codeChunk.content().contains("System.out.println(\"hello\")"));
        Assertions.assertTrue(codeChunk.content().endsWith("```"));
    }

    @Test
    void shouldFlushUnclosedCodeBlockAtEnd() {
        RagProperties properties = new RagProperties();
        properties.getChunking().setMaxChars(50);
        properties.getChunking().setOverlapChars(8);

        MarkdownChunker chunker = new MarkdownChunker(properties);
        String markdown = "## 调试\n\n"
                + "```python\n"
                + "def hello():\n"
                + "    return 1\n";

        List<Chunk> chunks = chunker.split(markdown);

        Chunk codeChunk = chunks.stream()
                .filter(c -> c.content().startsWith("```python"))
                .findFirst()
                .orElseThrow();
        Assertions.assertTrue(codeChunk.content().contains("def hello()"));
        Assertions.assertEquals("调试", codeChunk.titlePath());
    }

    @Test
    void shouldCreateSlidingWindowOverlapAndNeighborLinks() {
        RagProperties properties = new RagProperties();
        properties.getChunking().setMaxChars(260);
        properties.getChunking().setOverlapChars(100);

        MarkdownChunker chunker = new MarkdownChunker(properties);
        String markdown = "# 窗口测试\n\n" + buildTokenStream(420);

        List<Chunk> chunks = chunker.split(markdown);

        Assertions.assertTrue(chunks.size() >= 3);

        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            Assertions.assertEquals(i, chunk.chunkIndex());
            Assertions.assertEquals(i > 0 ? i - 1 : null, chunk.prevIndex());
            Assertions.assertEquals(i + 1 < chunks.size() ? i + 1 : null, chunk.nextIndex());
            Assertions.assertEquals(i > 0 ? chunks.get(i - 1) : null, chunk.getPrev(chunks));
            Assertions.assertEquals(i + 1 < chunks.size() ? chunks.get(i + 1) : null, chunk.getNext(chunks));
        }

        for (int i = 1; i < chunks.size(); i++) {
            int overlap = commonBoundaryLength(chunks.get(i - 1).content(), chunks.get(i).content());
            Assertions.assertTrue(overlap >= 80 && overlap <= 120,
                    "overlap out of expected range: " + overlap);
        }
    }

    @Test
    void shouldClampOverlapToMinimumBandWhenConfiguredTooSmall() {
        RagProperties properties = new RagProperties();
        properties.getChunking().setMaxChars(280);
        properties.getChunking().setOverlapChars(20);

        MarkdownChunker chunker = new MarkdownChunker(properties);
        String markdown = "# Clamp\n\n" + buildTokenStream(500);

        List<Chunk> chunks = chunker.split(markdown);

        Assertions.assertTrue(chunks.size() >= 3);
        int overlap = commonBoundaryLength(chunks.get(0).content(), chunks.get(1).content());
        Assertions.assertTrue(overlap >= 80 && overlap <= 120,
                "overlap should be clamped into [80,120], actual=" + overlap);
    }

    private static int commonBoundaryLength(String left, String right) {
        int max = Math.min(left.length(), right.length());
        for (int len = max; len > 0; len--) {
            int leftStart = left.length() - len;
            if (left.regionMatches(leftStart, right, 0, len)) {
                return len;
            }
        }
        return 0;
    }

    private static String buildTokenStream(int count) {
        StringBuilder builder = new StringBuilder(count * 6);
        for (int i = 0; i < count; i++) {
            builder.append(String.format("%04d|", i));
        }
        return builder.toString();
    }
}
