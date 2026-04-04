package com.zzp.rag.service.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.domain.trace.KnowledgeBaseTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class KnowledgeTraceService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTraceService.class);
    private static final String TRACE_KEY = "rag:trace:uploads";
    private static final int MAX_TRACE_SIZE = 300;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CopyOnWriteArrayList<KnowledgeBaseTrace> localFallback = new CopyOnWriteArrayList<>();

    public KnowledgeTraceService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(String knowledgeBaseId, String sessionId, String documentId, String fileName, int chunkCount) {
        KnowledgeBaseTrace trace = new KnowledgeBaseTrace(
                knowledgeBaseId,
                sessionId,
                documentId,
                fileName,
                chunkCount,
                Instant.now().toString());

        try {
            String raw = objectMapper.writeValueAsString(trace);
            redisTemplate.opsForList().leftPush(TRACE_KEY, raw);
            redisTemplate.opsForList().trim(TRACE_KEY, 0, MAX_TRACE_SIZE - 1L);
        } catch (Exception ex) {
            log.warn("Failed to save knowledge upload trace to redis: {}", ex.getMessage());
            localFallback.add(0, trace);
            while (localFallback.size() > MAX_TRACE_SIZE) {
                localFallback.remove(localFallback.size() - 1);
            }
        }
    }

    public List<KnowledgeBaseTrace> listAll() {
        try {
            List<String> list = redisTemplate.opsForList().range(TRACE_KEY, 0, MAX_TRACE_SIZE - 1L);
            if (list == null || list.isEmpty()) {
                return localFallback.stream().toList();
            }

            return list.stream().map(raw -> {
                try {
                    return objectMapper.readValue(raw, new TypeReference<KnowledgeBaseTrace>() {
                    });
                } catch (Exception parseEx) {
                    log.warn("Failed to parse knowledge trace: {}", parseEx.getMessage());
                    return null;
                }
            }).filter(v -> v != null).toList();
        } catch (Exception ex) {
            log.warn("Failed to list knowledge upload traces from redis: {}", ex.getMessage());
            return localFallback.stream().toList();
        }
    }

    public Optional<KnowledgeBaseTrace> findLatestByKnowledgeBaseId(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return Optional.empty();
        }
        String normalized = knowledgeBaseId.trim();
        return listAll().stream()
                .filter(v -> normalized.equals(v.knowledgeBaseId()))
                .findFirst();
    }
}


