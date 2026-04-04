package com.zzp.rag.domain.dto;

public record KnowledgeBaseDeleteResult(
        String knowledgeBaseId,
        String sessionId,
        int deletedVectors,
        int deletedSessionTurns,
        int deletedAuditRecords,
        int deletedCacheEntries,
        boolean traceDeleted,
        String message) {
}
