package com.zzp.rag.service;

import com.zzp.rag.domain.dto.KnowledgeBaseDeleteResult;
import com.zzp.rag.domain.trace.KnowledgeBaseTrace;
import com.zzp.rag.service.audit.QaAuditService;
import com.zzp.rag.service.cache.CacheService;
import com.zzp.rag.service.cache.SessionContextService;
import com.zzp.rag.service.retrieval.VectorStore;
import com.zzp.rag.service.trace.KnowledgeTraceService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

class KnowledgeBaseManagementServiceTest {

    @Test
    void shouldDeleteKnowledgeBaseAndReturnSummary() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        SessionContextService sessionContextService = Mockito.mock(SessionContextService.class);
        QaAuditService qaAuditService = Mockito.mock(QaAuditService.class);
        CacheService cacheService = Mockito.mock(CacheService.class);
        KnowledgeTraceService knowledgeTraceService = Mockito.mock(KnowledgeTraceService.class);

        KnowledgeBaseManagementService service = new KnowledgeBaseManagementService(
                vectorStore,
                sessionContextService,
                qaAuditService,
                cacheService,
                knowledgeTraceService);

        KnowledgeBaseTrace trace = new KnowledgeBaseTrace("kb-1", "session-kb-1", "doc-1", "a.md", 3, "now");
        Mockito.when(knowledgeTraceService.findLatestByKnowledgeBaseId("kb-1")).thenReturn(Optional.of(trace));
        Mockito.when(vectorStore.deleteByKnowledgeBaseId("kb-1")).thenReturn(5);
        Mockito.when(sessionContextService.deleteSession("session-kb-1")).thenReturn(2);
        Mockito.when(qaAuditService.deleteBySessionId("session-kb-1")).thenReturn(4);
        Mockito.when(cacheService.deleteByKnowledgeBaseId("kb-1")).thenReturn(3);
        Mockito.when(knowledgeTraceService.deleteByKnowledgeBaseId("kb-1")).thenReturn(Optional.of(trace));

        KnowledgeBaseDeleteResult result = service.deleteKnowledgeBase("kb-1");

        Assertions.assertEquals("kb-1", result.knowledgeBaseId());
        Assertions.assertEquals("session-kb-1", result.sessionId());
        Assertions.assertEquals(5, result.deletedVectors());
        Assertions.assertEquals(2, result.deletedSessionTurns());
        Assertions.assertEquals(4, result.deletedAuditRecords());
        Assertions.assertEquals(3, result.deletedCacheEntries());
        Assertions.assertTrue(result.traceDeleted());
    }

    @Test
    void shouldThrowWhenKnowledgeBaseNotFound() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        SessionContextService sessionContextService = Mockito.mock(SessionContextService.class);
        QaAuditService qaAuditService = Mockito.mock(QaAuditService.class);
        CacheService cacheService = Mockito.mock(CacheService.class);
        KnowledgeTraceService knowledgeTraceService = Mockito.mock(KnowledgeTraceService.class);

        KnowledgeBaseManagementService service = new KnowledgeBaseManagementService(
                vectorStore,
                sessionContextService,
                qaAuditService,
                cacheService,
                knowledgeTraceService);

        Mockito.when(knowledgeTraceService.findLatestByKnowledgeBaseId("missing-kb")).thenReturn(Optional.empty());
        Mockito.when(vectorStore.deleteByKnowledgeBaseId("missing-kb")).thenReturn(0);
        Mockito.when(cacheService.deleteByKnowledgeBaseId("missing-kb")).thenReturn(0);
        Mockito.when(knowledgeTraceService.deleteByKnowledgeBaseId("missing-kb")).thenReturn(Optional.empty());

        IllegalArgumentException ex = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.deleteKnowledgeBase("missing-kb"));

        Assertions.assertTrue(ex.getMessage().contains("知识库不存在或已删除"));
    }
}
