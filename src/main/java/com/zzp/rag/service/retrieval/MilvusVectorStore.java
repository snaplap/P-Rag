package com.zzp.rag.service.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.model.RetrievalChunk;
import com.zzp.rag.domain.model.VectorDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MilvusVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);
    private static final Pattern ASCII_TERM_PATTERN = Pattern.compile("[a-z0-9_\\-.]{2,}");
    private static final Pattern CJK_TERM_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}");
    private static final double BM25_K1 = 1.2d;
    private static final double BM25_B = 0.75d;

    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;
    private final Map<String, VectorDocument> localIndex = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private volatile boolean remoteCollectionReady;

    public MilvusVectorStore(RagProperties ragProperties, ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 向量写入入口：始终先写本地索引，再尝试远端 Milvus。
     * 注意：先本地后远端可保证远端故障时检索仍可用。
     */
    @Override
    public void upsert(VectorDocument vectorDocument) {
        // 始终先写本地索引，保障本地开发与容灾场景可检索。
        localIndex.put(vectorDocument.id(), vectorDocument);
        if (ragProperties.getMilvus().isUseRemote()) {
            pushToRemoteSafely(vectorDocument);
        }
    }

    /**
     * 向量检索入口：优先远端，失败时降级本地索引。
     */
    @Override
    public List<RetrievalChunk> search(double[] queryVector, int topK, String knowledgeBaseId) {
        // 为避免跨知识库混淆，检索阶段必须携带 knowledgeBaseId。
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return List.of();
        }

        if (ragProperties.getMilvus().isUseRemote()) {
            List<RetrievalChunk> remoteChunks = searchFromRemoteSafely(queryVector, topK, knowledgeBaseId);
            if (!remoteChunks.isEmpty()) {
                return remoteChunks;
            }
        }

        // 本地索引兜底检索路径。
        List<RetrievalChunk> chunks = new ArrayList<>();
        for (VectorDocument doc : localIndex.values()) {
            if (knowledgeBaseId != null && !knowledgeBaseId.equals(doc.knowledgeBaseId())) {
                continue;
            }
            double score = cosine(queryVector, doc.vector());
            chunks.add(new RetrievalChunk(
                    doc.id(),
                    doc.documentId(),
                    doc.content(),
                    score,
                    DataSourceType.KNOWLEDGE_BASE));
        }

        return chunks.stream()
                .sorted(Comparator.comparingDouble(RetrievalChunk::score).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    /**
     * 关键词检索入口：优先远端全文能力，失败时回退本地 BM25 近似实现。
     */
    @Override
    public List<RetrievalChunk> searchByKeywords(String query, int topK, String knowledgeBaseId) {
        if (query == null || query.isBlank() || knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return List.of();
        }

        if (ragProperties.getMilvus().isUseRemote()) {
            List<RetrievalChunk> remoteChunks = searchByKeywordsFromRemoteSafely(query, topK, knowledgeBaseId);
            if (!remoteChunks.isEmpty()) {
                return remoteChunks;
            }
        }

        return searchByKeywordsFromLocal(query, topK, knowledgeBaseId);
    }

    /**
     * 按知识库删除向量：本地先删，远端尽量删除。
     */
    @Override
    public int deleteByKnowledgeBaseId(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return 0;
        }

        String normalized = knowledgeBaseId.trim();
        int localDeleted = deleteFromLocalIndex(normalized);

        if (ragProperties.getMilvus().isUseRemote()) {
            deleteFromRemoteSafely(normalized);
        }
        return localDeleted;
    }

    /**
     * 本地索引删除实现。
     */
    private int deleteFromLocalIndex(String knowledgeBaseId) {
        int before = localIndex.size();
        localIndex.entrySet().removeIf(entry -> knowledgeBaseId.equals(entry.getValue().knowledgeBaseId()));
        return Math.max(0, before - localIndex.size());
    }

    /**
     * 远端 Milvus 删除实现，失败不抛出以避免影响主流程。
     */
    private void deleteFromRemoteSafely(String knowledgeBaseId) {
        try {
            ensureRemoteCollection();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("collectionName", ragProperties.getMilvus().getCollection());
            payload.put("filter", "knowledgeBaseId == \"" + escapeFilterValue(knowledgeBaseId) + "\"");

            postMilvus("/v2/vectordb/entities/delete", payload);
        } catch (Exception ex) {
            log.warn("Remote Milvus delete failed, local index already cleaned: {}", ex.getMessage());
        }
    }

    /**
     * 远端 Milvus upsert，异常时保持静默降级。
     */
    private void pushToRemoteSafely(VectorDocument vectorDocument) {
        try {
            ensureRemoteCollection();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", vectorDocument.id());
            row.put("knowledgeBaseId", vectorDocument.knowledgeBaseId());
            row.put("documentId", vectorDocument.documentId());
            row.put("content", vectorDocument.content());
            row.put("vector", vectorDocument.vector());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("collectionName", ragProperties.getMilvus().getCollection());
            payload.put("data", List.of(row));

            postMilvus("/v2/vectordb/entities/insert", payload);
        } catch (Exception ex) {
            log.warn("Remote Milvus upsert failed, fallback local only: {}", ex.getMessage());
        }
    }

    /**
     * 远端向量检索，返回统一 RetrievalChunk。
     */
    private List<RetrievalChunk> searchFromRemoteSafely(double[] queryVector, int topK, String knowledgeBaseId) {
        try {
            ensureRemoteCollection();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("collectionName", ragProperties.getMilvus().getCollection());
            payload.put("data", List.of(queryVector));
            payload.put("annsField", "vector");
            payload.put("limit", Math.max(1, topK));
            payload.put("outputFields", List.of("id", "knowledgeBaseId", "documentId", "content"));
            payload.put("filter", "knowledgeBaseId == \"" + knowledgeBaseId + "\"");

            Map<String, Object> response = postMilvus("/v2/vectordb/entities/search", payload);
            Object rawData = response.get("data");
            if (!(rawData instanceof List<?> rawList) || rawList.isEmpty()) {
                return List.of();
            }

            List<RetrievalChunk> chunks = new ArrayList<>();
            for (Object item : rawList) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }

                String id = String.valueOf(map.get("id"));
                String kbId = String.valueOf(map.get("knowledgeBaseId"));
                String documentId = String.valueOf(map.get("documentId"));
                String content = String.valueOf(map.get("content"));
                double score = parseScore(map.get("score"));

                if (!knowledgeBaseId.equals(kbId)) {
                    continue;
                }

                chunks.add(new RetrievalChunk(id, documentId, content, score, DataSourceType.KNOWLEDGE_BASE));
            }
            return chunks;
        } catch (Exception ex) {
            log.warn("Remote Milvus search failed, fallback local index: {}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * 远端关键词检索：尝试 Milvus 原生全文接口，失败后返回空由上层走本地兜底。
     */
    private List<RetrievalChunk> searchByKeywordsFromRemoteSafely(String query, int topK, String knowledgeBaseId) {
        List<String> searchPaths = List.of(
                "/v2/vectordb/entities/text_search",
                "/v2/vectordb/entities/search");

        for (String path : searchPaths) {
            try {
                ensureRemoteCollection();

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("collectionName", ragProperties.getMilvus().getCollection());
                payload.put("limit", Math.max(1, topK));
                payload.put("outputFields", List.of("id", "knowledgeBaseId", "documentId", "content"));
                payload.put("filter", "knowledgeBaseId == \"" + escapeFilterValue(knowledgeBaseId) + "\"");

                if ("/v2/vectordb/entities/text_search".equals(path)) {
                    payload.put("query", query);
                } else {
                    payload.put("annsField", "content");
                    payload.put("data", List.of(query));
                }

                Map<String, Object> response = postMilvus(path, payload);
                List<RetrievalChunk> parsed = parseKeywordSearchResponse(response, knowledgeBaseId);
                if (!parsed.isEmpty()) {
                    return parsed.stream()
                            .sorted(Comparator.comparingDouble(RetrievalChunk::score).reversed())
                            .limit(Math.max(1, topK))
                            .toList();
                }
            } catch (Exception ex) {
                log.debug("Remote Milvus keyword search path={} failed: {}", path, ex.getMessage());
            }
        }

        log.info("Remote Milvus keyword search unavailable, fallback to local BM25 index.");
        return List.of();
    }

    /**
     * 本地 BM25 近似关键词检索，用于远端不可用时兜底。
     */
    private List<RetrievalChunk> searchByKeywordsFromLocal(String query, int topK, String knowledgeBaseId) {
        List<String> terms = extractTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        List<VectorDocument> docs = localIndex.values().stream()
                .filter(doc -> knowledgeBaseId.equals(doc.knowledgeBaseId()))
                .filter(doc -> doc.content() != null && !doc.content().isBlank())
                .toList();
        if (docs.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> docFrequency = new LinkedHashMap<>();
        for (String term : terms) {
            int df = 0;
            for (VectorDocument doc : docs) {
                if (containsTerm(doc.content(), term)) {
                    df++;
                }
            }
            if (df > 0) {
                docFrequency.put(term, df);
            }
        }
        if (docFrequency.isEmpty()) {
            return List.of();
        }

        double avgDocLength = docs.stream().mapToInt(this::documentLength).average().orElse(1.0d);
        int docCount = docs.size();
        List<RetrievalChunk> scored = new ArrayList<>();

        for (VectorDocument doc : docs) {
            double bm25 = bm25Score(doc, docFrequency, docCount, avgDocLength);
            if (bm25 <= 0.0d) {
                continue;
            }

            scored.add(new RetrievalChunk(
                    doc.id(),
                    doc.documentId(),
                    doc.content(),
                    bm25,
                    DataSourceType.KNOWLEDGE_BASE));
        }

        if (scored.isEmpty()) {
            return List.of();
        }

        return normalizeScores(scored).stream()
                .sorted(Comparator.comparingDouble(RetrievalChunk::score).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    private List<RetrievalChunk> parseKeywordSearchResponse(Map<String, Object> response, String knowledgeBaseId) {
        Object rawData = response.get("data");
        if (!(rawData instanceof List<?> rawList) || rawList.isEmpty()) {
            return List.of();
        }

        List<RetrievalChunk> chunks = new ArrayList<>();
        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }

            Map<?, ?> entity = map.get("entity") instanceof Map<?, ?> entityMap ? entityMap : Map.of();
            String kbId = stringValue(map.get("knowledgeBaseId"), entity.get("knowledgeBaseId"));
            if (!knowledgeBaseId.equals(kbId)) {
                continue;
            }

            String id = stringValue(map.get("id"), entity.get("id"));
            String documentId = stringValue(map.get("documentId"), entity.get("documentId"));
            String content = stringValue(map.get("content"), entity.get("content"));
            if (content.isBlank()) {
                continue;
            }

            double relevance = parseKeywordRelevance(map);
            chunks.add(new RetrievalChunk(id, documentId, content, relevance, DataSourceType.KNOWLEDGE_BASE));
        }
        return normalizeScores(chunks);
    }

    private double parseKeywordRelevance(Map<?, ?> map) {
        if (map.get("score") instanceof Number score) {
            return score.doubleValue();
        }
        if (map.get("distance") instanceof Number distance) {
            return 1.0d / (1.0d + Math.max(0.0d, distance.doubleValue()));
        }
        return parseScore(map.get("score"));
    }

    private double bm25Score(VectorDocument doc, Map<String, Integer> docFrequency, int docCount, double avgDocLength) {
        String content = doc.content() == null ? "" : doc.content();
        int docLength = Math.max(1, documentLength(doc));

        double score = 0.0d;
        for (Map.Entry<String, Integer> entry : docFrequency.entrySet()) {
            String term = entry.getKey();
            int df = entry.getValue();
            int tf = termFrequency(content, term);
            if (tf <= 0) {
                continue;
            }

            double idf = Math.log(1.0d + (docCount - df + 0.5d) / (df + 0.5d));
            double numerator = tf * (BM25_K1 + 1.0d);
            double denominator = tf + BM25_K1 * (1.0d - BM25_B + BM25_B * docLength / Math.max(1.0d, avgDocLength));
            score += idf * (numerator / Math.max(1.0e-9, denominator));
        }
        return score;
    }

    private List<RetrievalChunk> normalizeScores(List<RetrievalChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        double min = chunks.stream().mapToDouble(RetrievalChunk::score).min().orElse(0.0d);
        double max = chunks.stream().mapToDouble(RetrievalChunk::score).max().orElse(1.0d);
        if (max <= 1.0d && min >= 0.0d) {
            return chunks.stream()
                    .map(chunk -> new RetrievalChunk(
                            chunk.id(),
                            chunk.documentId(),
                            chunk.content(),
                            clampScore(chunk.score()),
                            chunk.sourceType()))
                    .toList();
        }

        double gap = max - min;
        if (gap <= 1.0e-9) {
            return chunks.stream()
                    .map(chunk -> new RetrievalChunk(
                            chunk.id(),
                            chunk.documentId(),
                            chunk.content(),
                            1.0d,
                            chunk.sourceType()))
                    .toList();
        }

        return chunks.stream()
                .map(chunk -> new RetrievalChunk(
                        chunk.id(),
                        chunk.documentId(),
                        chunk.content(),
                        clampScore((chunk.score() - min) / gap),
                        chunk.sourceType()))
                .toList();
    }

    private List<String> extractTerms(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        if (normalized.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> terms = new LinkedHashSet<>();

        Matcher asciiMatcher = ASCII_TERM_PATTERN.matcher(normalized);
        while (asciiMatcher.find() && terms.size() < 12) {
            terms.add(asciiMatcher.group());
        }

        Matcher cjkMatcher = CJK_TERM_PATTERN.matcher(normalized);
        while (cjkMatcher.find() && terms.size() < 12) {
            String token = cjkMatcher.group();
            if (token.length() <= 4) {
                terms.add(token);
                continue;
            }
            for (int i = 0; i < token.length() - 1 && terms.size() < 12; i++) {
                terms.add(token.substring(i, i + 2));
            }
        }

        return terms.stream().toList();
    }

    private boolean containsTerm(String content, String term) {
        return termFrequency(content, term) > 0;
    }

    private int termFrequency(String content, String term) {
        String normalizedContent = content == null ? "" : content.toLowerCase(Locale.ROOT);
        if (normalizedContent.isBlank() || term == null || term.isBlank()) {
            return 0;
        }

        if (ASCII_TERM_PATTERN.matcher(term).matches()) {
            Pattern boundaryPattern = Pattern.compile("(?<![a-z0-9_\\-.])" + Pattern.quote(term) + "(?![a-z0-9_\\-.])");
            Matcher matcher = boundaryPattern.matcher(normalizedContent);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            return count;
        }

        int count = 0;
        int fromIndex = 0;
        while (true) {
            int idx = normalizedContent.indexOf(term, fromIndex);
            if (idx < 0) {
                break;
            }
            count++;
            fromIndex = idx + term.length();
        }
        return count;
    }

    private int documentLength(VectorDocument doc) {
        String content = doc.content() == null ? "" : doc.content().trim();
        if (content.isBlank()) {
            return 1;
        }

        String[] words = content.split("\\s+");
        if (words.length > 1) {
            return words.length;
        }
        return content.length();
    }

    private String stringValue(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (!text.isBlank() && !"null".equalsIgnoreCase(text)) {
                return text;
            }
        }
        return "";
    }

    /**
     * 容错解析检索分数。
     */
    private double parseScore(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null) {
            return 0.0d;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception ex) {
            return 0.0d;
        }
    }

    /**
     * 转义 Milvus filter 字符串。
     */
    private String escapeFilterValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 保证远端集合存在，首次调用时懒加载创建。
     */
    private void ensureRemoteCollection() throws Exception {
        if (remoteCollectionReady) {
            return;
        }

        synchronized (this) {
            if (remoteCollectionReady) {
                return;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("collectionName", ragProperties.getMilvus().getCollection());
            payload.put("dimension", ragProperties.getEmbedding().getDimension());
            payload.put("metricType", "COSINE");

            // 若集合已存在，Milvus 可能返回异常信息；这里吞掉异常继续使用。
            try {
                postMilvus("/v2/vectordb/collections/create", payload);
            } catch (Exception ignored) {
                // ignore
            }
            remoteCollectionReady = true;
        }
    }

    /**
     * 统一封装 Milvus HTTP POST 调用。
     */
    private Map<String, Object> postMilvus(String path, Map<String, Object> payload) throws Exception {
        String url = ragProperties.getMilvus().getBaseUrl() + path;
        String json = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Milvus status=" + response.statusCode());
        }

        if (response.body() == null || response.body().isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(response.body(), new TypeReference<>() {
        });
    }

    /**
     * 余弦相似度计算。
     */
    private double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0d;
        }

        double dot = 0.0d;
        double normA = 0.0d;
        double normB = 0.0d;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0d || normB == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private double clampScore(double score) {
        return Math.max(0.0d, Math.min(1.0d, score));
    }
}
