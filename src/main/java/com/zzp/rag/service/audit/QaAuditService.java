package com.zzp.rag.service.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.domain.model.DataSourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
/**
 * 问答审计日志服务。
 */
public class QaAuditService {

    private static final Logger log = LoggerFactory.getLogger(QaAuditService.class);
    private static final String AUDIT_KEY = "rag:trace:qa";
    private static final int MAX_AUDIT_SIZE = 2000;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CopyOnWriteArrayList<String> localFallback = new CopyOnWriteArrayList<>();

    public QaAuditService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 插入单条审计记录，失败时降级到本地内存。
     */
    public void safeInsert(String sessionId, String question, DataSourceType source, boolean uncertain,
            boolean cacheHit) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("sessionId", sessionId);
        record.put("question", question);
        record.put("source", source.name());
        record.put("uncertain", uncertain);
        record.put("cacheHit", cacheHit);
        record.put("createdAt", Instant.now().toString());

        try {
            String raw = objectMapper.writeValueAsString(record);
            redisTemplate.opsForList().leftPush(AUDIT_KEY, raw);
            redisTemplate.opsForList().trim(AUDIT_KEY, 0, MAX_AUDIT_SIZE - 1L);
        } catch (Exception ex) {
            log.warn("Failed to persist qa audit record to redis: {}", ex.getMessage());
            localFallback.add(0, String.valueOf(record));
            while (localFallback.size() > MAX_AUDIT_SIZE) {
                localFallback.remove(localFallback.size() - 1);
            }
        }
    }

    /**
     * 删除指定会话的审计记录。
     */
    public int deleteBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        String normalized = sessionId.trim();
        int removed = 0;

        try {
            List<String> list = redisTemplate.opsForList().range(AUDIT_KEY, 0, MAX_AUDIT_SIZE - 1L);
            if (list != null && !list.isEmpty()) {
                List<String> kept = list.stream().filter(raw -> {
                    if (isAuditRecordForSession(raw, normalized)) {
                        return false;
                    }
                    return true;
                }).toList();

                removed += Math.max(0, list.size() - kept.size());
                if (removed > 0) {
                    redisTemplate.delete(AUDIT_KEY);
                    if (!kept.isEmpty()) {
                        redisTemplate.opsForList().rightPushAll(AUDIT_KEY, kept);
                        redisTemplate.opsForList().trim(AUDIT_KEY, 0, MAX_AUDIT_SIZE - 1L);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to delete qa audit records from redis: {}", ex.getMessage());
        }

        int before = localFallback.size();
        localFallback.removeIf(raw -> isAuditRecordForSession(raw, normalized));
        removed += Math.max(0, before - localFallback.size());
        return removed;
    }

    /**
     * 判断审计记录是否属于指定会话。
     */
    private boolean isAuditRecordForSession(String raw, String sessionId) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            String recordSessionId = objectMapper.readTree(raw).path("sessionId").asText("");
            return sessionId.equals(recordSessionId);
        } catch (Exception ignored) {
            return raw.contains("sessionId=" + sessionId) || raw.contains("\"sessionId\":\"" + sessionId + "\"");
        }
    }
}
