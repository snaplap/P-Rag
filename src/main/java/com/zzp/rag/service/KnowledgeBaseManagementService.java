package com.zzp.rag.service;

import com.zzp.rag.domain.dto.KnowledgeBaseDeleteResult;
import com.zzp.rag.domain.trace.KnowledgeBaseTrace;
import com.zzp.rag.service.audit.QaAuditService;
import com.zzp.rag.service.cache.CacheService;
import com.zzp.rag.service.cache.SessionContextService;
import com.zzp.rag.service.retrieval.VectorStore;
import com.zzp.rag.service.trace.KnowledgeTraceService;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class KnowledgeBaseManagementService {

    private final VectorStore vectorStore;
    private final SessionContextService sessionContextService;
    private final QaAuditService qaAuditService;
    private final CacheService cacheService;
    private final KnowledgeTraceService knowledgeTraceService;

    public KnowledgeBaseManagementService(
            VectorStore vectorStore,
            SessionContextService sessionContextService,
            QaAuditService qaAuditService,
            CacheService cacheService,
            KnowledgeTraceService knowledgeTraceService) {
        this.vectorStore = vectorStore;
        this.sessionContextService = sessionContextService;
        this.qaAuditService = qaAuditService;
        this.cacheService = cacheService;
        this.knowledgeTraceService = knowledgeTraceService;
    }

    public KnowledgeBaseDeleteResult deleteKnowledgeBase(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            throw new IllegalArgumentException("knowledgeBaseId 不能为空");
        }
        String normalizedKnowledgeBaseId = knowledgeBaseId.trim();

        Optional<KnowledgeBaseTrace> trace = knowledgeTraceService
                .findLatestByKnowledgeBaseId(normalizedKnowledgeBaseId);
        String sessionId = trace.map(KnowledgeBaseTrace::sessionId).orElse(null);

        int deletedVectors = vectorStore.deleteByKnowledgeBaseId(normalizedKnowledgeBaseId);
        int deletedSessionTurns = sessionId == null || sessionId.isBlank() ? 0
                : sessionContextService.deleteSession(sessionId);
        int deletedAuditRecords = sessionId == null || sessionId.isBlank() ? 0
                : qaAuditService.deleteBySessionId(sessionId);
        int deletedCacheEntries = cacheService.deleteByKnowledgeBaseId(normalizedKnowledgeBaseId);
        boolean traceDeleted = knowledgeTraceService.deleteByKnowledgeBaseId(normalizedKnowledgeBaseId).isPresent();

        boolean noResourceDeleted = deletedVectors == 0
                && deletedSessionTurns == 0
                && deletedAuditRecords == 0
                && deletedCacheEntries == 0
                && !traceDeleted;
        if (noResourceDeleted) {
            throw new IllegalArgumentException("知识库不存在或已删除: " + normalizedKnowledgeBaseId);
        }

        return new KnowledgeBaseDeleteResult(
                normalizedKnowledgeBaseId,
                sessionId,
                deletedVectors,
                deletedSessionTurns,
                deletedAuditRecords,
                deletedCacheEntries,
                traceDeleted,
                "知识库及关联记录删除完成");
    }
}
