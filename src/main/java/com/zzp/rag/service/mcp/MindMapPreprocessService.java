package com.zzp.rag.service.mcp;

import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.model.RetrievalChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class MindMapPreprocessService {

    private final RagProperties ragProperties;

    public MindMapPreprocessService(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public String compressAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }

        String normalized = answer
                .replace("不确定性声明：", "")
                .replaceAll("\\s+", " ")
                .trim();

        int maxChars = Math.max(120, ragProperties.getMcp().getMindMapMaxAnswerChars());
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars);
    }

    public List<RetrievalChunk> preprocessEvidence(List<RetrievalChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }

        int maxEvidence = Math.max(1, ragProperties.getMcp().getMindMapMaxEvidenceItems());
        int snippetChars = Math.max(10, ragProperties.getMcp().getMindMapSnippetChars());

        List<RetrievalChunk> sorted = evidence.stream()
                .filter(v -> v != null && v.content() != null && !v.content().isBlank())
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .toList();

        Set<String> dedup = new LinkedHashSet<>();
        List<RetrievalChunk> result = new ArrayList<>();
        for (RetrievalChunk chunk : sorted) {
            if (result.size() >= maxEvidence) {
                break;
            }

            String key = evidenceKey(chunk);
            if (!dedup.add(key)) {
                continue;
            }

            String compact = chunk.content().replaceAll("\\s+", " ").trim();
            if (compact.length() > snippetChars) {
                compact = compact.substring(0, snippetChars);
            }

            result.add(new RetrievalChunk(
                    chunk.id(),
                    chunk.documentId(),
                    compact,
                    chunk.score(),
                    chunk.sourceType()));
        }
        return result;
    }

    public List<Map<String, Object>> buildEvidenceBindings(List<RetrievalChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> bindings = new ArrayList<>();
        for (int i = 0; i < evidence.size(); i++) {
            RetrievalChunk chunk = evidence.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bindingId", "evidence-" + (i + 1));
            item.put("chunkId", chunk.id());
            item.put("documentId", chunk.documentId());
            item.put("sourceType", chunk.sourceType() == null ? "UNKNOWN" : chunk.sourceType().name());
            item.put("snippet", chunk.content());
            bindings.add(item);
        }
        return bindings;
    }

    private String evidenceKey(RetrievalChunk chunk) {
        String doc = chunk.documentId() == null ? "" : chunk.documentId().trim().toLowerCase(Locale.ROOT);
        String content = chunk.content() == null ? ""
                : chunk.content().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        if (content.length() > 80) {
            content = content.substring(0, 80);
        }
        return doc + "|" + content;
    }
}
