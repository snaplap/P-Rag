package com.zzp.rag.service.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.domain.model.DataSourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
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
}


