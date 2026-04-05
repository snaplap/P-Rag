package com.zzp.rag.service.retrieval;

import com.zzp.rag.domain.model.RetrievalChunk;
import com.zzp.rag.service.embedding.EmbeddingService;
import com.zzp.rag.service.rerank.RerankService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class RetrievalService {

    private static final double BALANCED_MIN_SCORE = 0.42d;
    private static final double EDGE_SCORE_MARGIN = 0.08d;

    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final RerankService rerankService;

    public RetrievalService(EmbeddingService embeddingService, VectorStore vectorStore, RerankService rerankService) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.rerankService = rerankService;
    }

    /**
     * 检索入口：向量召回 + 重排 + 平衡过滤。
     * 注意：topK 会被保护为最小 1，避免上游传入非法值导致空结果。
     */
    public List<RetrievalChunk> retrieve(String question, int topK, String knowledgeBaseId) {
        int safeTopK = Math.max(1, topK);
        double[] queryVector = embeddingService.embed(question);
        // 先扩大候选池，再执行模型重排，提升召回稳定性。
        List<RetrievalChunk> candidates = vectorStore.search(queryVector, Math.max(12, safeTopK * 3), knowledgeBaseId);

        try {
            List<RetrievalChunk> reranked = rerankService.rerank(question, candidates);
            return postProcess(reranked, safeTopK);
        } catch (RuntimeException ex) {
            return postProcess(candidates, safeTopK);
        }
    }

    /**
     * 检索后处理：排序、阈值过滤、去重，并保留少量边缘证据。
     * 注意：这里不是最激进过滤，而是“平衡过滤”，目的是兼顾准确性与覆盖率。
     */
    private List<RetrievalChunk> postProcess(List<RetrievalChunk> chunks, int topK) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        List<RetrievalChunk> sorted = chunks.stream()
                .filter(v -> v != null && v.content() != null && !v.content().isBlank())
                .sorted(Comparator.comparingDouble(RetrievalChunk::score).reversed())
                .toList();
        if (sorted.isEmpty()) {
            return List.of();
        }

        double topScore = clampScore(sorted.get(0).score());
        double balancedThreshold = Math.max(BALANCED_MIN_SCORE, Math.min(0.75d, topScore - 0.35d));
        double edgeThreshold = Math.max(0.0d, balancedThreshold - EDGE_SCORE_MARGIN);

        List<RetrievalChunk> highQuality = uniqueChunks(sorted, balancedThreshold, topK);
        if (highQuality.isEmpty()) {
            highQuality.add(sorted.get(0));
        }
        if (highQuality.size() >= topK) {
            return highQuality.subList(0, topK);
        }

        List<RetrievalChunk> result = new ArrayList<>(highQuality);
        Set<String> existing = new LinkedHashSet<>();
        for (RetrievalChunk chunk : result) {
            existing.add(chunkKey(chunk));
        }
        for (RetrievalChunk chunk : sorted) {
            if (result.size() >= topK) {
                break;
            }
            if (clampScore(chunk.score()) < edgeThreshold) {
                continue;
            }
            String key = chunkKey(chunk);
            if (existing.add(key)) {
                result.add(chunk);
            }
        }
        return result;
    }

    /**
     * 选取高质量且去重后的证据。
     */
    private List<RetrievalChunk> uniqueChunks(List<RetrievalChunk> sorted, double threshold, int topK) {
        List<RetrievalChunk> result = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();
        for (RetrievalChunk chunk : sorted) {
            if (clampScore(chunk.score()) < threshold) {
                continue;
            }
            String key = chunkKey(chunk);
            if (dedup.add(key)) {
                result.add(chunk);
                if (result.size() >= topK) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 构建去重键：来源 + 内容前缀。
     */
    private String chunkKey(RetrievalChunk chunk) {
        if (chunk == null) {
            return "";
        }
        String source = chunk.documentId() == null ? "" : chunk.documentId().trim().toLowerCase(Locale.ROOT);
        String content = chunk.content() == null ? ""
                : chunk.content().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        if (content.length() > 120) {
            content = content.substring(0, 120);
        }
        return source + "|" + content;
    }

    /**
     * 分数裁剪到 [0,1]。
     */
    private double clampScore(double score) {
        return Math.max(0.0d, Math.min(1.0d, score));
    }
}
