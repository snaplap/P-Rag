package com.zzp.rag.service.retrieval;

import com.zzp.rag.domain.model.RetrievalChunk;
import com.zzp.rag.service.embedding.EmbeddingService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RetrievalService {

    private static final double BALANCED_MIN_SCORE = 0.42d;
    private static final double EDGE_SCORE_MARGIN = 0.08d;
    private static final int RRF_K = 30;

    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;

    public RetrievalService(EmbeddingService embeddingService, VectorStore vectorStore) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
    }

    /**
     * 检索入口：默认使用原问题作为检索查询。
     */
    public List<RetrievalChunk> retrieve(String question, int topK, String knowledgeBaseId) {
        return retrieve(question, question, topK, knowledgeBaseId);
    }

    /**
     * 检索入口：双路召回（语义路 + 关键词路）融合后执行平衡过滤。
     */
    public List<RetrievalChunk> retrieve(String question, String retrievalQuery, int topK, String knowledgeBaseId) {
        int safeTopK = Math.max(1, topK);
        int candidatePoolSize = Math.max(12, safeTopK * 3);

        String normalizedQuestion = question == null ? "" : question.trim();
        String normalizedRetrievalQuery = retrievalQuery == null || retrievalQuery.isBlank()
                ? normalizedQuestion
                : retrievalQuery.trim();

        List<RetrievalChunk> semanticRoute = retrieveByVectorQuery(
                normalizedRetrievalQuery,
                candidatePoolSize,
                knowledgeBaseId);

        List<RetrievalChunk> keywordRoute = vectorStore.searchByKeywords(
                normalizedRetrievalQuery,
                candidatePoolSize,
                knowledgeBaseId);

        List<RetrievalChunk> fusedCandidates = fuseMultiRouteCandidates(semanticRoute, keywordRoute);
        return postProcess(fusedCandidates, safeTopK);
    }

    private List<RetrievalChunk> retrieveByVectorQuery(String query, int candidatePoolSize, String knowledgeBaseId) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        double[] queryVector = embeddingService.embed(query);
        return vectorStore.search(queryVector, candidatePoolSize, knowledgeBaseId);
    }

    /**
     * 双路候选融合：使用 RRF 归一化分数与基础相似度加权。
     */
    private List<RetrievalChunk> fuseMultiRouteCandidates(List<RetrievalChunk> semanticRoute,
            List<RetrievalChunk> keywordRoute) {
        if ((semanticRoute == null || semanticRoute.isEmpty()) && (keywordRoute == null || keywordRoute.isEmpty())) {
            return List.of();
        }

        Map<String, Integer> semanticRank = buildRankMap(semanticRoute);
        Map<String, Integer> keywordRank = buildRankMap(keywordRoute);

        Map<String, RetrievalChunk> bestChunkByKey = new HashMap<>();
        Map<String, Double> bestBaseScoreByKey = new HashMap<>();
        mergeBestChunk(semanticRoute, bestChunkByKey, bestBaseScoreByKey);
        mergeBestChunk(keywordRoute, bestChunkByKey, bestBaseScoreByKey);

        Map<String, Double> rrfScoreByKey = new HashMap<>();
        accumulateRrfScore(semanticRank, rrfScoreByKey);
        accumulateRrfScore(keywordRank, rrfScoreByKey);

        double maxRrfScore = rrfScoreByKey.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0d);
        if (maxRrfScore <= 0.0d) {
            maxRrfScore = 1.0d;
        }

        List<RetrievalChunk> fused = new ArrayList<>();
        for (Map.Entry<String, RetrievalChunk> entry : bestChunkByKey.entrySet()) {
            String key = entry.getKey();
            RetrievalChunk chunk = entry.getValue();

            double baseScore = clampScore(bestBaseScoreByKey.getOrDefault(key, 0.0d));
            double normalizedRrf = rrfScoreByKey.getOrDefault(key, 0.0d) / maxRrfScore;
            double multiRouteBonus = semanticRank.containsKey(key) && keywordRank.containsKey(key) ? 0.08d : 0.0d;
            double blendedScore = clampScore((baseScore * 0.72d) + (normalizedRrf * 0.28d) + multiRouteBonus);

            fused.add(new RetrievalChunk(
                    chunk.id(),
                    chunk.documentId(),
                    chunk.content(),
                    blendedScore,
                    chunk.sourceType()));
        }

        return fused.stream()
                .sorted(Comparator.comparingDouble(RetrievalChunk::score).reversed())
                .toList();
    }

    private Map<String, Integer> buildRankMap(List<RetrievalChunk> routeChunks) {
        if (routeChunks == null || routeChunks.isEmpty()) {
            return Map.of();
        }

        Map<String, Integer> rankMap = new HashMap<>();
        List<RetrievalChunk> sorted = routeChunks.stream()
                .filter(v -> v != null && v.content() != null && !v.content().isBlank())
                .sorted(Comparator.comparingDouble(RetrievalChunk::score).reversed())
                .toList();

        int rank = 0;
        for (RetrievalChunk chunk : sorted) {
            String key = chunkKey(chunk);
            if (rankMap.containsKey(key)) {
                continue;
            }
            rankMap.put(key, rank++);
        }
        return rankMap;
    }

    private void mergeBestChunk(List<RetrievalChunk> routeChunks,
            Map<String, RetrievalChunk> bestChunkByKey,
            Map<String, Double> bestBaseScoreByKey) {
        if (routeChunks == null || routeChunks.isEmpty()) {
            return;
        }

        for (RetrievalChunk chunk : routeChunks) {
            if (chunk == null || chunk.content() == null || chunk.content().isBlank()) {
                continue;
            }

            String key = chunkKey(chunk);
            double score = clampScore(chunk.score());
            double previous = bestBaseScoreByKey.getOrDefault(key, -1.0d);
            if (score > previous) {
                bestBaseScoreByKey.put(key, score);
                bestChunkByKey.put(key, chunk);
            }
        }
    }

    private void accumulateRrfScore(Map<String, Integer> rankMap, Map<String, Double> rrfScoreByKey) {
        for (Map.Entry<String, Integer> entry : rankMap.entrySet()) {
            String key = entry.getKey();
            int rank = entry.getValue();
            double score = 1.0d / (RRF_K + rank + 1.0d);
            rrfScoreByKey.merge(key, score, Double::sum);
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
