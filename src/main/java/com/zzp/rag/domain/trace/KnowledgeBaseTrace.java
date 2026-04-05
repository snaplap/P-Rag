package com.zzp.rag.domain.trace;

/**
 * 知识库上传追踪记录。
 */
public record KnowledgeBaseTrace(
                String knowledgeBaseId,
                String sessionId,
                String documentId,
                String fileName,
                int chunkCount,
                String createdAt) {
}
