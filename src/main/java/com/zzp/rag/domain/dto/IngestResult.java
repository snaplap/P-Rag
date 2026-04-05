package com.zzp.rag.domain.dto;

/**
 * Markdown 摄入结果。
 */
public record IngestResult(
        String knowledgeBaseId,
        String sessionId,
        String documentId,
        String fileName,
        int chunkCount,
        String message) {
}
