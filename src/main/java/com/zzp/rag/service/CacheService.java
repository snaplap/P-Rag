package com.zzp.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.RagAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;
    private final Map<String, LocalCacheEntry> localFallback = new ConcurrentHashMap<>();

    public CacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, RagProperties ragProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
    }

    public Optional<RagAnswer> get(String question) {
        String key = buildKey(question);

        try {
            // 优先读取 Redis（生产路径）。
            String raw = redisTemplate.opsForValue().get(key);
            if (raw != null) {
                return Optional.of(objectMapper.readValue(raw, RagAnswer.class));
            }
        } catch (Exception ex) {
            log.warn("Redis read failed, use local cache fallback: {}", ex.getMessage());
        }

        // Redis 不可用时退化到进程内缓存，保证演示和开发场景可继续工作。
        LocalCacheEntry entry = localFallback.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAtMillis < System.currentTimeMillis()) {
            localFallback.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value);
    }

    public void put(String question, RagAnswer answer) {
        String key = buildKey(question);
        long ttl = Math.max(30L, ragProperties.getCache().getTtlSeconds());

        try {
            String raw = objectMapper.writeValueAsString(answer);
            redisTemplate.opsForValue().set(key, raw, Duration.ofSeconds(ttl));
        } catch (Exception ex) {
            log.warn("Redis write failed, use local cache fallback: {}", ex.getMessage());
            // 回退缓存同样带 TTL，避免内存无限增长。
            localFallback.put(key, new LocalCacheEntry(answer, System.currentTimeMillis() + (ttl * 1000L)));
        }
    }

    private String buildKey(String question) {
        String normalized = question == null ? "" : question.trim().toLowerCase();
        String hash = md5Hex(normalized);
        return ragProperties.getCache().getKeyPrefix() + hash;
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

    private record LocalCacheEntry(RagAnswer value, long expiresAtMillis) {
    }
}
