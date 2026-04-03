package com.zzp.rag.service;

import com.zzp.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class MarkdownChunker {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？.!?；;])\\s+");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*$");

    private final RagProperties ragProperties;

    public MarkdownChunker(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public List<String> split(String markdown) {
        int maxChars = Math.max(200, ragProperties.getChunking().getMaxChars());
        int overlapChars = Math.max(0, ragProperties.getChunking().getOverlapChars());

        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        String normalized = markdown.replace("\r", "").trim();
        List<String> sections = splitByHeading(normalized);
        List<String> chunks = new ArrayList<>();

        for (String section : sections) {
            if (section.isBlank()) {
                continue;
            }

            List<String> paragraphs = splitByParagraph(section);
            StringBuilder buffer = new StringBuilder();

            for (String paragraph : paragraphs) {
                if (paragraph.isBlank()) {
                    continue;
                }

                // 短段优先按段聚合，避免语义被切碎。
                if (paragraph.length() <= maxChars) {
                    if (buffer.length() + paragraph.length() + 1 > maxChars) {
                        flushChunk(chunks, buffer, overlapChars);
                    }
                    append(buffer, paragraph);
                    continue;
                }

                // 超长段按句子切，尽量在自然语义边界断开。
                String[] sentences = SENTENCE_SPLIT.split(paragraph);
                for (String sentence : sentences) {
                    String seg = sentence.trim();
                    if (seg.isBlank()) {
                        continue;
                    }

                    if (seg.length() > maxChars) {
                        flushChunk(chunks, buffer, overlapChars);
                        splitHard(chunks, seg, maxChars, overlapChars);
                        continue;
                    }

                    if (buffer.length() + seg.length() + 1 > maxChars) {
                        flushChunk(chunks, buffer, overlapChars);
                    }
                    append(buffer, seg);
                }
            }
            flushChunk(chunks, buffer, overlapChars);
        }

        return deduplicate(chunks);
    }

    private List<String> splitByHeading(String markdown) {
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : markdown.split("\\n")) {
            String trimmed = line.trim();
            if (HEADING.matcher(trimmed).matches() && current.length() > 0) {
                sections.add(current.toString().trim());
                current.setLength(0);
            }
            append(current, line);
        }

        if (current.length() > 0) {
            sections.add(current.toString().trim());
        }
        return sections;
    }

    private List<String> splitByParagraph(String section) {
        List<String> paragraphs = new ArrayList<>();
        for (String block : section.split("\\n\\s*\\n+")) {
            String p = block.trim();
            if (!p.isBlank()) {
                paragraphs.add(p);
            }
        }
        if (paragraphs.isEmpty()) {
            paragraphs.add(section.trim());
        }
        return paragraphs;
    }

    private void splitHard(List<String> chunks, String text, int maxChars, int overlapChars) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            String piece = text.substring(start, end).trim();
            if (!piece.isBlank()) {
                chunks.add(piece);
            }
            if (end == text.length()) {
                break;
            }
            start = Math.max(end - overlapChars, start + 1);
        }
    }

    private void flushChunk(List<String> chunks, StringBuilder buffer, int overlapChars) {
        if (buffer.length() == 0) {
            return;
        }

        String chunk = buffer.toString().trim();
        if (!chunk.isBlank()) {
            chunks.add(chunk);
        }

        if (overlapChars > 0 && chunk.length() > overlapChars) {
            String tail = chunk.substring(chunk.length() - overlapChars).trim();
            buffer.setLength(0);
            buffer.append(tail);
        } else {
            buffer.setLength(0);
        }
    }

    private void append(StringBuilder builder, String text) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(text);
    }

    private List<String> deduplicate(List<String> chunks) {
        List<String> unique = new ArrayList<>();
        String prev = null;
        for (String chunk : chunks) {
            String normalized = chunk.trim();
            if (normalized.isBlank()) {
                continue;
            }
            if (prev != null && prev.equals(normalized)) {
                continue;
            }
            unique.add(normalized);
            prev = normalized;
        }
        return unique;
    }
}
