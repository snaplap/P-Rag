package com.zzp.rag.service.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.domain.trace.KnowledgeBaseTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
/**
 * 知识库上传追踪服务。
 */
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

    /**
     * 保存上传追踪记录。
     */
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

    /**
     * 列出最近上传追踪记录。
     */
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

    /**
     * 查找指定知识库最新上传记录。
     */
    public Optional<KnowledgeBaseTrace> findLatestByKnowledgeBaseId(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return Optional.empty();
        }
        String normalized = knowledgeBaseId.trim();
        return listAll().stream()
                .filter(v -> normalized.equals(v.knowledgeBaseId()))
                .findFirst();
    }

    /**
     * 删除指定知识库追踪记录。
     */
    public Optional<KnowledgeBaseTrace> deleteByKnowledgeBaseId(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return Optional.empty();
        }

        String normalized = knowledgeBaseId.trim();
        Optional<KnowledgeBaseTrace> deleted = Optional.empty();

        try {
            List<String> list = redisTemplate.opsForList().range(TRACE_KEY, 0, MAX_TRACE_SIZE - 1L);
            if (list != null && !list.isEmpty()) {
                List<String> kept = new ArrayList<>();
                for (String raw : list) {
                    KnowledgeBaseTrace trace = parseTrace(raw);
                    if (trace != null && normalized.equals(trace.knowledgeBaseId())) {
                        if (deleted.isEmpty()) {
                            deleted = Optional.of(trace);
                        }
                    } else {
                        kept.add(raw);
                    }
                }

                if (kept.size() != list.size()) {
                    redisTemplate.delete(TRACE_KEY);
                    if (!kept.isEmpty()) {
                        redisTemplate.opsForList().rightPushAll(TRACE_KEY, kept);
                        redisTemplate.opsForList().trim(TRACE_KEY, 0, MAX_TRACE_SIZE - 1L);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to delete knowledge upload traces from redis: {}", ex.getMessage());
        }

        Optional<KnowledgeBaseTrace> localDeleted = localFallback.stream()
                .filter(v -> normalized.equals(v.knowledgeBaseId()))
                .findFirst();
        localFallback.removeIf(v -> normalized.equals(v.knowledgeBaseId()));

        return deleted.isPresent() ? deleted : localDeleted;
    }

    /**
     * 解析追踪 JSON。
     */
    private KnowledgeBaseTrace parseTrace(String raw) {
        try {
            return objectMapper.readValue(raw, new TypeReference<KnowledgeBaseTrace>() {
            });
        } catch (Exception parseEx) {
            log.warn("Failed to parse knowledge trace: {}", parseEx.getMessage());
            return null;
        }
    }
}
