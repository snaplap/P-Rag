package com.zzp.rag.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.model.RetrievalChunk;
import com.zzp.rag.config.RagProperties;
import com.zzp.rag.service.embedding.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
/**
 * 联网检索向量缓存服务。
 */
public class WebSearchVectorCacheService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchVectorCacheService.class);
    private static final String KEY_PREFIX = "rag:web:vec:";
    private static final long TTL_SECONDS = 6 * 3600L;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;
    private final EmbeddingVectorCacheService embeddingVectorCacheService;
    private final RagProperties ragProperties;

    public WebSearchVectorCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            EmbeddingService embeddingService,
            EmbeddingVectorCacheService embeddingVectorCacheService,
            RagProperties ragProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.embeddingService = embeddingService;
        this.embeddingVectorCacheService = embeddingVectorCacheService;
        this.ragProperties = ragProperties;
    }

    /**
     * 将联网证据向量化后缓存到 Redis，便于后续复用。
     */
    public void cache(String question, List<RetrievalChunk> webEvidence) {
        if (question == null || question.isBlank() || webEvidence == null || webEvidence.isEmpty()) {
            return;
        }

        try {
            List<RetrievalChunk> webChunks = webEvidence.stream()
                    .filter(v -> v != null && v.sourceType() == DataSourceType.WEB)
                    .toList();

            if (webChunks.isEmpty()) {
                return;
            }

            Map<String, double[]> vectorsByText = buildVectorsByText(webChunks);
            List<Map<String, Object>> vectorized = new ArrayList<>(webChunks.size());
            for (RetrievalChunk chunk : webChunks) {
                String text = chunk.content() == null ? "" : chunk.content().trim();
                double[] vector = vectorsByText.get(text);
                if (vector == null) {
                    vector = embeddingService.embed(text);
                    embeddingVectorCacheService.putBatch(Map.of(text, vector));
                }
                vectorized.add(toVectorDoc(chunk, vector));
            }

            if (vectorized.isEmpty()) {
                return;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("question", question);
            payload.put("createdAt", Instant.now().toString());
            payload.put("items", vectorized);

            String key = KEY_PREFIX + md5Hex(question.trim().toLowerCase());
            String raw = objectMapper.writeValueAsString(payload);
            redisTemplate.opsForValue().set(key, raw, Duration.ofSeconds(TTL_SECONDS));
        } catch (Exception ex) {
            log.warn("Failed to cache vectorized web evidence: {}", ex.getMessage());
        }
    }

    /**
     * 将检索片段转换为可序列化向量文档。
     */
    private Map<String, Object> toVectorDoc(RetrievalChunk chunk, double[] vector) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", chunk.id());
        doc.put("documentId", chunk.documentId());
        doc.put("content", chunk.content());
        doc.put("score", chunk.score());
        doc.put("source", chunk.sourceType().name());
        doc.put("vector", vector);
        return doc;
    }

    private Map<String, double[]> buildVectorsByText(List<RetrievalChunk> chunks) {
        List<String> uniqueTexts = new ArrayList<>(new LinkedHashSet<>(chunks.stream()
                .map(c -> c.content() == null ? "" : c.content().trim())
                .filter(text -> !text.isBlank())
                .toList()));

        Map<String, double[]> vectorsByText = new LinkedHashMap<>(embeddingVectorCacheService.getBatch(uniqueTexts));
        List<String> misses = uniqueTexts.stream()
                .filter(text -> !vectorsByText.containsKey(text))
                .toList();
        if (misses.isEmpty()) {
            return vectorsByText;
        }

        int batchSize = normalizeBatchSize(ragProperties.getEmbedding().getBatchSize());
        Map<String, double[]> fresh = new LinkedHashMap<>();
        for (int start = 0; start < misses.size(); start += batchSize) {
            int end = Math.min(start + batchSize, misses.size());
            List<String> batch = misses.subList(start, end);
            List<double[]> vectors = embeddingService.embedBatch(batch);
            for (int i = 0; i < batch.size(); i++) {
                if (i < vectors.size() && vectors.get(i) != null) {
                    fresh.put(batch.get(i), vectors.get(i));
                }
            }
        }

        if (!fresh.isEmpty()) {
            embeddingVectorCacheService.putBatch(fresh);
            vectorsByText.putAll(fresh);
        }
        return vectorsByText;
    }

    private int normalizeBatchSize(int configured) {
        if (configured < 10) {
            return 10;
        }
        return Math.min(configured, 100);
    }

    /**
     * 计算问题哈希键。
     */
    private String md5Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
