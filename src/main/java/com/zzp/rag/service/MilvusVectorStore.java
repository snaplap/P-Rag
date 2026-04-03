package com.zzp.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.DataSourceType;
import com.zzp.rag.domain.RetrievalChunk;
import com.zzp.rag.domain.VectorDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Component
public class MilvusVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);

    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;
    private final Map<String, VectorDocument> localIndex = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private volatile boolean remoteCollectionReady;

    public MilvusVectorStore(RagProperties ragProperties, ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void upsert(VectorDocument vectorDocument) {
        // 永远先写本地索引，保证本地开发和容灾场景可检索。
        localIndex.put(vectorDocument.id(), vectorDocument);
        if (ragProperties.getMilvus().isUseRemote()) {
            pushToRemoteSafely(vectorDocument);
        }
    }

    @Override
    public List<RetrievalChunk> search(double[] queryVector, int topK) {
        if (ragProperties.getMilvus().isUseRemote()) {
            List<RetrievalChunk> remoteChunks = searchFromRemoteSafely(queryVector, topK);
            if (!remoteChunks.isEmpty()) {
                return remoteChunks;
            }
        }

        // 首版默认从本地索引返回；后续可替换为远端 Milvus 搜索结果。
        List<RetrievalChunk> chunks = new ArrayList<>();
        for (VectorDocument doc : localIndex.values()) {
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

    private void pushToRemoteSafely(VectorDocument vectorDocument) {
        try {
            ensureRemoteCollection();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", vectorDocument.id());
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

    private List<RetrievalChunk> searchFromRemoteSafely(double[] queryVector, int topK) {
        try {
            ensureRemoteCollection();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("collectionName", ragProperties.getMilvus().getCollection());
            payload.put("data", List.of(queryVector));
            payload.put("annsField", "vector");
            payload.put("limit", Math.max(1, topK));
            payload.put("outputFields", List.of("id", "documentId", "content"));

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

                String id = String.valueOf(map.getOrDefault("id", ""));
                String documentId = String.valueOf(map.getOrDefault("documentId", ""));
                String content = String.valueOf(map.getOrDefault("content", ""));
                double score = parseScore(map.get("score"));

                chunks.add(new RetrievalChunk(id, documentId, content, score, DataSourceType.KNOWLEDGE_BASE));
            }
            return chunks;
        } catch (Exception ex) {
            log.warn("Remote Milvus search failed, fallback local index: {}", ex.getMessage());
            return List.of();
        }
    }

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

            // 若集合已存在，Milvus 会返回异常信息，直接吞掉并继续。
            try {
                postMilvus("/v2/vectordb/collections/create", payload);
            } catch (Exception ignored) {
                // ignore
            }
            remoteCollectionReady = true;
        }
    }

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
}
