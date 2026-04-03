package com.zzp.rag.service;

import com.zzp.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class MarkdownChunker {

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
        String[] blocks = normalized.split("\\n\\s*\\n+");
        List<String> chunks = new ArrayList<>();

        for (String rawBlock : blocks) {
            String block = rawBlock.trim();
            if (block.isBlank()) {
                continue;
            }
            if (block.length() <= maxChars) {
                chunks.add(block);
                continue;
            }

            int start = 0;
            while (start < block.length()) {
                int end = Math.min(start + maxChars, block.length());
                String chunk = block.substring(start, end).trim();
                if (!chunk.isBlank()) {
                    chunks.add(chunk);
                }
                if (end == block.length()) {
                    break;
                }
                start = Math.max(end - overlapChars, start + 1);
            }
        }

        return chunks;
    }
}
