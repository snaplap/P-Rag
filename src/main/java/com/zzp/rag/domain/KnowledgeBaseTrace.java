package com.zzp.rag.domain;

public record KnowledgeBaseTrace(
        String knowledgeBaseId,
        String sessionId,
        String documentId,
        String fileName,
        int chunkCount,
        String createdAt) {
}
