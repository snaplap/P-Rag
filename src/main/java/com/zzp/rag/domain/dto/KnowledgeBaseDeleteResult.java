package com.zzp.rag.domain.dto;

/**
 * 删除知识库后的聚合结果。
 */
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
