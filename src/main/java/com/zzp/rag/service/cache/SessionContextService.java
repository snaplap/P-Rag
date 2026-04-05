package com.zzp.rag.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.trace.ConversationTurn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
/**
 * 会话上下文服务，维护最近 N 轮问答历史。
 */
public class SessionContextService {

    private static final Logger log = LoggerFactory.getLogger(SessionContextService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;
    private final Map<String, Deque<ConversationTurn>> localSessions = new ConcurrentHashMap<>();

    public SessionContextService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
            RagProperties ragProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
    }

    /**
     * 读取会话历史，优先 Redis，失败时降级本地内存。
     */
    public List<ConversationTurn> load(String sessionId) {
        String key = buildKey(sessionId);
        int maxHistory = Math.max(1, ragProperties.getSession().getMaxHistory());

        try {
            List<String> rawList = redisTemplate.opsForList().range(key, 0, maxHistory - 1);
            if (rawList != null && !rawList.isEmpty()) {
                List<ConversationTurn> turns = new ArrayList<>();
                for (String raw : rawList) {
                    turns.add(objectMapper.readValue(raw, ConversationTurn.class));
                }
                Collections.reverse(turns);
                return turns;
            }
        } catch (Exception ex) {
            log.warn("Redis session read failed, use local session fallback: {}", ex.getMessage());
        }

        Deque<ConversationTurn> deque = localSessions.get(key);
        if (deque == null || deque.isEmpty()) {
            return List.of();
        }
        List<ConversationTurn> turns = new ArrayList<>(deque);
        Collections.reverse(turns);
        return turns;
    }

    /**
     * 追加一轮会话并控制历史长度。
     */
    public void append(String sessionId, ConversationTurn turn) {
        String key = buildKey(sessionId);
        int maxHistory = Math.max(1, ragProperties.getSession().getMaxHistory());
        long ttl = Math.max(60L, ragProperties.getSession().getTtlSeconds());

        try {
            String raw = objectMapper.writeValueAsString(turn);
            redisTemplate.opsForList().leftPush(key, raw);
            redisTemplate.opsForList().trim(key, 0, maxHistory - 1L);
            redisTemplate.expire(key, Duration.ofSeconds(ttl));
            return;
        } catch (Exception ex) {
            log.warn("Redis session write failed, use local session fallback: {}", ex.getMessage());
        }

        Deque<ConversationTurn> deque = localSessions.computeIfAbsent(key, k -> new ArrayDeque<>());
        deque.addFirst(turn);
        while (deque.size() > maxHistory) {
            deque.removeLast();
        }
    }

    /**
     * 删除指定会话历史。
     */
    public int deleteSession(String sessionId) {
        String key = buildKey(sessionId);
        int removed = 0;

        try {
            Long size = redisTemplate.opsForList().size(key);
            Boolean deleted = redisTemplate.delete(key);
            if (Boolean.TRUE.equals(deleted)) {
                removed += size == null ? 0 : size.intValue();
            }
        } catch (Exception ex) {
            log.warn("Redis session delete failed, use local session fallback: {}", ex.getMessage());
        }

        Deque<ConversationTurn> deque = localSessions.remove(key);
        if (deque != null) {
            removed += deque.size();
        }
        return removed;
    }

    /**
     * 构建会话缓存键。
     */
    private String buildKey(String sessionId) {
        String sid = (sessionId == null || sessionId.isBlank()) ? "anonymous" : sessionId.trim();
        return ragProperties.getSession().getKeyPrefix() + sid;
    }
}
