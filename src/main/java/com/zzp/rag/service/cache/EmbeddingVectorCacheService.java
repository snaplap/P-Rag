package com.zzp.rag.service.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
/**
 * embedding 向量缓存：优先 Redis，异常时回退本地内存。
 */
public class EmbeddingVectorCacheService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingVectorCacheService.class);
    private static final String KEY_PREFIX = "rag:embedding:vec:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;
    private final Map<String, LocalEntry> localFallback = new ConcurrentHashMap<>();

    public EmbeddingVectorCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RagProperties ragProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
    }

    /**
     * 批量读取向量缓存，命中结果按文本原值索引返回。
     */
    public Map<String, double[]> getBatch(List<String> texts) {
        Map<String, double[]> hits = new LinkedHashMap<>();
        if (!isEnabled() || texts == null || texts.isEmpty()) {
            return hits;
        }

        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            String normalized = text.trim();
            String key = buildKey(normalized);

            double[] vector = readFromRedis(key);
            if (vector == null) {
                vector = readFromLocal(key);
            }
            if (vector != null) {
                hits.put(normalized, vector);
            }
        }
        return hits;
    }

    /**
     * 批量写入向量缓存。
     */
    public void putBatch(Map<String, double[]> vectorsByText) {
        if (!isEnabled() || vectorsByText == null || vectorsByText.isEmpty()) {
            return;
        }

        long ttl = Math.max(60L, ragProperties.getEmbedding().getCacheTtlSeconds());
        long expiresAt = System.currentTimeMillis() + ttl * 1000L;

        for (Map.Entry<String, double[]> entry : vectorsByText.entrySet()) {
            String text = entry.getKey();
            double[] vector = entry.getValue();
            if (text == null || text.isBlank() || vector == null || vector.length == 0) {
                continue;
            }

            String normalized = text.trim();
            String key = buildKey(normalized);
            localFallback.put(key, new LocalEntry(vector, expiresAt));

            try {
                String raw = objectMapper.writeValueAsString(vector);
                redisTemplate.opsForValue().set(key, raw, Duration.ofSeconds(ttl));
            } catch (Exception ex) {
                log.warn("Failed to put embedding cache into redis, fallback to local only: {}", ex.getMessage());
            }
        }
    }

    private double[] readFromRedis(String key) {
        try {
            String raw = redisTemplate.opsForValue().get(key);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return objectMapper.readValue(raw, new TypeReference<double[]>() {
            });
        } catch (Exception ex) {
            log.warn("Failed to read embedding cache from redis, fallback to local: {}", ex.getMessage());
            return null;
        }
    }

    private double[] readFromLocal(String key) {
        LocalEntry entry = localFallback.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtMillis < System.currentTimeMillis()) {
            localFallback.remove(key);
            return null;
        }
        return copyVector(entry.vector);
    }

    private String buildKey(String text) {
        return KEY_PREFIX + md5Hex(text);
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

    private boolean isEnabled() {
        return ragProperties.getEmbedding().isCacheEnabled();
    }

    private double[] copyVector(double[] source) {
        double[] copy = new double[source.length];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private record LocalEntry(double[] vector, long expiresAtMillis) {
    }
}