package com.zzp.rag.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.model.RetrievalChunk;
import com.zzp.rag.service.embedding.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WebSearchVectorCacheService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchVectorCacheService.class);
    private static final String KEY_PREFIX = "rag:web:vec:";
    private static final long TTL_SECONDS = 6 * 3600L;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;

    public WebSearchVectorCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            EmbeddingService embeddingService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.embeddingService = embeddingService;
    }

    public void cache(String question, List<RetrievalChunk> webEvidence) {
        if (question == null || question.isBlank() || webEvidence == null || webEvidence.isEmpty()) {
            return;
        }

        try {
            List<Map<String, Object>> vectorized = webEvidence.stream()
                    .filter(v -> v != null && v.sourceType() == DataSourceType.WEB)
                    .map(this::toVectorDoc)
                    .toList();

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

    private Map<String, Object> toVectorDoc(RetrievalChunk chunk) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", chunk.id());
        doc.put("documentId", chunk.documentId());
        doc.put("content", chunk.content());
        doc.put("score", chunk.score());
        doc.put("source", chunk.sourceType().name());
        doc.put("vector", embeddingService.embed(chunk.content()));
        return doc;
    }

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
