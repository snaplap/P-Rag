package com.zzp.rag.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.dto.RagAnswer;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private static final String CACHE_INDEX_PREFIX = "rag:answer:index:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;
    private final Map<String, LocalCacheEntry> localFallback = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> localCacheIndexByKb = new ConcurrentHashMap<>();

    public CacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, RagProperties ragProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
    }

    /**
     * 优先从 Redis 读取缓存；当 Redis 异常时降级到进程内缓存。
     */
    public Optional<RagAnswer> get(String question, String knowledgeBaseId) {
        String key = buildKey(question, knowledgeBaseId);

        try {
            // 优先读取 Redis（生产主路径）。
            String raw = redisTemplate.opsForValue().get(key);
            if (raw != null) {
                return Optional.of(objectMapper.readValue(raw, RagAnswer.class));
            }
        } catch (Exception ex) {
            log.warn("Redis read failed, use local cache fallback: {}", ex.getMessage());
        }

        // Redis 不可用时退化到本地缓存，保证演示和开发阶段可持续工作。
        LocalCacheEntry entry = localFallback.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAtMillis < System.currentTimeMillis()) {
            localFallback.remove(key);
            untrackLocalKey(entry.normalizedKnowledgeBaseId, key);
            return Optional.empty();
        }
        return Optional.of(entry.value);
    }

    /**
     * 写入回答缓存，并维护按知识库维度的索引，便于后续批量删除。
     */
    public void put(String question, String knowledgeBaseId, RagAnswer answer) {
        String key = buildKey(question, knowledgeBaseId);
        String normalizedKnowledgeBaseId = normalizeKnowledgeBaseId(knowledgeBaseId);
        long ttl = Math.max(30L, ragProperties.getCache().getTtlSeconds());

        try {
            String raw = objectMapper.writeValueAsString(answer);
            redisTemplate.opsForValue().set(key, raw, Duration.ofSeconds(ttl));
            redisTemplate.opsForSet().add(buildIndexKey(normalizedKnowledgeBaseId), key);
            redisTemplate.expire(buildIndexKey(normalizedKnowledgeBaseId), Duration.ofSeconds(ttl));
        } catch (Exception ex) {
            log.warn("Redis write failed, use local cache fallback: {}", ex.getMessage());
            // 回退缓存同样受 TTL 控制，避免内存无限增长。
            localFallback.put(
                    key,
                    new LocalCacheEntry(answer, System.currentTimeMillis() + (ttl * 1000L), normalizedKnowledgeBaseId));
        }

        trackLocalKey(normalizedKnowledgeBaseId, key);
    }

    /**
     * 按知识库删除缓存，优先 Redis 索引，再清理本地回退缓存。
     */
    public int deleteByKnowledgeBaseId(String knowledgeBaseId) {
        String normalizedKnowledgeBaseId = normalizeKnowledgeBaseId(knowledgeBaseId);
        int removed = 0;

        try {
            String indexKey = buildIndexKey(normalizedKnowledgeBaseId);
            Set<String> cacheKeys = redisTemplate.opsForSet().members(indexKey);
            if (cacheKeys != null && !cacheKeys.isEmpty()) {
                Long deleted = redisTemplate.delete(cacheKeys);
                removed += deleted == null ? 0 : deleted.intValue();
            }
            redisTemplate.delete(indexKey);
        } catch (Exception ex) {
            log.warn("Redis delete by knowledge base failed, use local cache index fallback: {}", ex.getMessage());
        }

        Set<String> localKeys = localCacheIndexByKb.remove(normalizedKnowledgeBaseId);
        if (localKeys != null && !localKeys.isEmpty()) {
            for (String key : localKeys) {
                if (localFallback.remove(key) != null) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * 通过“知识库 + 问题归一化文本”生成稳定缓存键。
     */
    private String buildKey(String question, String knowledgeBaseId) {
        String normalized = question == null ? "" : question.trim().toLowerCase();
        String kb = normalizeKnowledgeBaseId(knowledgeBaseId);
        String hash = md5Hex(kb + "::" + normalized);
        return ragProperties.getCache().getKeyPrefix() + hash;
    }

    /**
     * 构建知识库维度的缓存索引键。
     */
    private String buildIndexKey(String normalizedKnowledgeBaseId) {
        return CACHE_INDEX_PREFIX + normalizedKnowledgeBaseId;
    }

    /**
     * 归一化知识库标识；缺省值统一映射为 global。
     */
    private String normalizeKnowledgeBaseId(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return "global";
        }
        return knowledgeBaseId.trim().toLowerCase();
    }

    /**
     * 在本地索引中登记缓存键。
     */
    private void trackLocalKey(String normalizedKnowledgeBaseId, String key) {
        localCacheIndexByKb
                .computeIfAbsent(normalizedKnowledgeBaseId, ignored -> ConcurrentHashMap.newKeySet())
                .add(key);
    }

    /**
     * 在本地索引中移除缓存键；索引为空时同步删除索引集合。
     */
    private void untrackLocalKey(String normalizedKnowledgeBaseId, String key) {
        Set<String> keys = localCacheIndexByKb.get(normalizedKnowledgeBaseId);
        if (keys == null) {
            return;
        }
        keys.remove(key);
        if (keys.isEmpty()) {
            localCacheIndexByKb.remove(normalizedKnowledgeBaseId);
        }
    }

    /**
     * 计算字符串 MD5；若算法不可用则回退 hashCode。
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

    private record LocalCacheEntry(RagAnswer value, long expiresAtMillis, String normalizedKnowledgeBaseId) {
    }
}
