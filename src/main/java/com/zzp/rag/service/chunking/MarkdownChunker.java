package com.zzp.rag.service.chunking;

import com.zzp.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MarkdownChunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+(.+)$");

    private final RagProperties ragProperties;

    public MarkdownChunker(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * 将 Markdown 按“标题 -> 段落”切分后，使用显式滑动窗口构建重叠 chunk。
     */
    public List<Chunk> split(String markdown) {
        int maxChars = Math.max(200, ragProperties.getChunking().getMaxChars());
        int overlapChars = normalizeOverlap(ragProperties.getChunking().getOverlapChars(), maxChars);

        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        String normalized = markdown.replace("\r", "").trim();
        List<Section> sections = splitByHeading(normalized);
        List<Chunk> chunks = new ArrayList<>();
        int[] chunkIndex = new int[] { 0 };

        for (Section section : sections) {
            if (section.content().isBlank()) {
                continue;
            }

            List<ParagraphUnit> paragraphs = splitByParagraph(section.content());
            StringBuilder textBuffer = new StringBuilder();

            for (ParagraphUnit paragraphUnit : paragraphs) {
                if (paragraphUnit.codeBlock()) {
                    flushTextBufferWithSlidingWindow(chunks, textBuffer, maxChars, overlapChars,
                            section.titlePath(), chunkIndex);
                    addChunk(chunks, paragraphUnit.text(), section.titlePath(), chunkIndex);
                    continue;
                }

                String paragraph = paragraphUnit.text();
                if (paragraph.isBlank()) {
                    continue;
                }

                // 文本段先拼成完整语义块，再统一走滑动窗口切分。
                appendParagraph(textBuffer, paragraph);
            }

            flushTextBufferWithSlidingWindow(chunks, textBuffer, maxChars, overlapChars,
                    section.titlePath(), chunkIndex);
        }

        return deduplicateAndLink(chunks);
    }

    /**
     * 按 Markdown 标题分节，确保不同主题不会混到同一个 chunk。
     */
    private List<Section> splitByHeading(String markdown) {
        List<Section> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        List<String> headingStack = new ArrayList<>();
        String currentTitlePath = "";
        boolean inCodeBlock = false;

        for (String line : markdown.split("\\n", -1)) {
            String trimmed = line.trim();

            if (isCodeFence(trimmed)) {
                inCodeBlock = !inCodeBlock;
                append(current, line);
                continue;
            }

            Matcher headingMatcher = HEADING.matcher(trimmed);
            if (!inCodeBlock && headingMatcher.matches()) {
                if (current.length() > 0) {
                    String content = current.toString().trim();
                    if (!content.isBlank()) {
                        sections.add(new Section(currentTitlePath, content));
                    }
                }

                current.setLength(0);
                int level = headingMatcher.group(1).length();
                String titleText = headingMatcher.group(2).trim();
                shrinkHeadingStack(headingStack, level);
                headingStack.add(titleText);
                currentTitlePath = String.join(" > ", headingStack);
                continue;
            }

            append(current, line);
        }

        if (current.length() > 0) {
            String content = current.toString().trim();
            if (!content.isBlank()) {
                sections.add(new Section(currentTitlePath, content));
            }
        }

        return sections;
    }

    /**
     * 按空行分段，保留段落级语义结构。
     */
    private List<ParagraphUnit> splitByParagraph(String section) {
        List<ParagraphUnit> paragraphs = new ArrayList<>();
        StringBuilder textBuffer = new StringBuilder();
        StringBuilder codeBuffer = new StringBuilder();
        boolean inCodeBlock = false;

        for (String line : section.split("\\n", -1)) {
            String trimmed = line.trim();
            if (isCodeFence(trimmed)) {
                if (inCodeBlock) {
                    append(codeBuffer, line);
                    addCodeParagraph(paragraphs, codeBuffer.toString());
                    codeBuffer.setLength(0);
                    inCodeBlock = false;
                } else {
                    addTextParagraphs(paragraphs, textBuffer.toString());
                    textBuffer.setLength(0);
                    append(codeBuffer, line);
                    inCodeBlock = true;
                }
                continue;
            }

            if (inCodeBlock) {
                append(codeBuffer, line);
            } else {
                append(textBuffer, line);
            }
        }

        if (inCodeBlock && codeBuffer.length() > 0) {
            addCodeParagraph(paragraphs, codeBuffer.toString());
        }
        addTextParagraphs(paragraphs, textBuffer.toString());

        if (paragraphs.isEmpty()) {
            paragraphs.add(new ParagraphUnit(section.trim(), false));
        }
        return paragraphs;
    }

    private int normalizeOverlap(int configuredOverlap, int maxChars) {
        int safeMax = Math.max(1, maxChars);
        int upperBound = Math.max(0, safeMax - 1);

        // 大窗口场景强制收敛到 80-120，避免重叠不足或过大导致召回质量波动。
        if (safeMax > 120) {
            int bounded = Math.max(80, Math.min(120, configuredOverlap));
            return Math.min(bounded, upperBound);
        }

        // 小窗口测试场景允许使用更小 overlap，避免 step<=0。
        return Math.max(0, Math.min(configuredOverlap, upperBound));
    }

    private void flushTextBufferWithSlidingWindow(List<Chunk> chunks, StringBuilder textBuffer,
            int maxChars, int overlapChars, String titlePath, int[] chunkIndex) {
        if (textBuffer.length() == 0) {
            return;
        }

        String text = textBuffer.toString().trim();
        textBuffer.setLength(0);
        if (text.isBlank()) {
            return;
        }

        splitBySlidingWindow(chunks, text, maxChars, overlapChars, titlePath, chunkIndex);
    }

    private void splitBySlidingWindow(List<Chunk> chunks, String text, int maxChars, int overlapChars,
            String titlePath, int[] chunkIndex) {
        if (text.length() <= maxChars) {
            addChunk(chunks, text, titlePath, chunkIndex);
            return;
        }

        int step = Math.max(1, maxChars - overlapChars);
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + maxChars, text.length());
            String piece = text.substring(start, end).trim();
            if (!piece.isBlank()) {
                addChunk(chunks, piece, titlePath, chunkIndex);
            }
            if (end >= text.length()) {
                break;
            }
        }
    }

    private void appendParagraph(StringBuilder builder, String paragraph) {
        String normalized = paragraph == null ? "" : paragraph.trim();
        if (normalized.isBlank()) {
            return;
        }

        if (builder.length() > 0) {
            builder.append("\n\n");
        }
        builder.append(normalized);
    }

    /**
     * 统一追加文本，块内以换行拼接。
     */
    private void append(StringBuilder builder, String text) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(text);
    }

    /**
     * 去除相邻重复 chunk，并回填 prev/next 索引引用链。
     */
    private List<Chunk> deduplicateAndLink(List<Chunk> chunks) {
        List<Chunk> unique = new ArrayList<>();
        String previousKey = null;
        for (Chunk chunk : chunks) {
            String normalized = chunk.content().trim();
            String titlePath = chunk.titlePath() == null ? "" : chunk.titlePath().trim();
            if (normalized.isBlank()) {
                continue;
            }

            String dedupeKey = titlePath + "\n" + normalized;
            if (dedupeKey.equals(previousKey)) {
                continue;
            }

            unique.add(new Chunk(normalized, titlePath, chunk.chunkIndex(), null, null));
            previousKey = dedupeKey;
        }

        List<Chunk> linked = new ArrayList<>(unique.size());
        for (int i = 0; i < unique.size(); i++) {
            Chunk current = unique.get(i);
            Integer prevIndex = i > 0 ? i - 1 : null;
            Integer nextIndex = i + 1 < unique.size() ? i + 1 : null;
            linked.add(new Chunk(current.content(), current.titlePath(), i, prevIndex, nextIndex));
        }
        return linked;
    }

    private void addChunk(List<Chunk> chunks, String content, String titlePath, int[] chunkIndex) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isBlank()) {
            return;
        }
        String safeTitlePath = titlePath == null ? "" : titlePath.trim();
        chunks.add(new Chunk(normalized, safeTitlePath, chunkIndex[0], null, null));
        chunkIndex[0] = chunkIndex[0] + 1;
    }

    private void addTextParagraphs(List<ParagraphUnit> paragraphs, String textBuffer) {
        String normalized = textBuffer == null ? "" : textBuffer.trim();
        if (normalized.isBlank()) {
            return;
        }
        for (String block : normalized.split("\\n\\s*\\n+")) {
            String p = block.trim();
            if (!p.isBlank()) {
                paragraphs.add(new ParagraphUnit(p, false));
            }
        }
    }

    private void addCodeParagraph(List<ParagraphUnit> paragraphs, String codeBlock) {
        String normalized = codeBlock == null ? "" : codeBlock.trim();
        if (!normalized.isBlank()) {
            paragraphs.add(new ParagraphUnit(normalized, true));
        }
    }

    private boolean isCodeFence(String trimmedLine) {
        return trimmedLine.startsWith("```");
    }

    private void shrinkHeadingStack(List<String> headingStack, int level) {
        while (headingStack.size() >= level) {
            headingStack.remove(headingStack.size() - 1);
        }
    }

    private record Section(String titlePath, String content) {
    }

    private record ParagraphUnit(String text, boolean codeBlock) {
    }
}
