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
        // 姘歌繙鍏堝啓鏈湴绱㈠紩锛屼繚璇佹湰鍦板紑鍙戝拰瀹圭伨鍦烘櫙鍙绱€?
        localIndex.put(vectorDocument.id(), vectorDocument);
        if (ragProperties.getMilvus().isUseRemote()) {
            pushToRemoteSafely(vectorDocument);
        }
    }

    @Override
    public List<RetrievalChunk> search(double[] queryVector, int topK, String knowledgeBaseId) {
        // 涓洪伩鍏嶈法鐭ヨ瘑搴撴贩娣嗭紝妫€绱㈤樁娈靛繀椤绘惡甯?knowledgeBaseId銆?
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return List.of();
        }

        if (ragProperties.getMilvus().isUseRemote()) {
            List<RetrievalChunk> remoteChunks = searchFromRemoteSafely(queryVector, topK, knowledgeBaseId);
            if (!remoteChunks.isEmpty()) {
                return remoteChunks;
            }
        }

        // 棣栫増榛樿浠庢湰鍦扮储寮曡繑鍥烇紱鍚庣画鍙浛鎹负杩滅 Milvus 鎼滅储缁撴灉銆?
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

    private int deleteFromLocalIndex(String knowledgeBaseId) {
        int before = localIndex.size();
        localIndex.entrySet().removeIf(entry -> knowledgeBaseId.equals(entry.getValue().knowledgeBaseId()));
        return Math.max(0, before - localIndex.size());
    }

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

    private String escapeFilterValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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

            // 鑻ラ泦鍚堝凡瀛樺湪锛孧ilvus 浼氳繑鍥炲紓甯镐俊鎭紝鐩存帴鍚炴帀骞剁户缁€?
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
