package com.zzp.rag.domain;

public record IngestResult(
                String knowledgeBaseId,
                String sessionId,
                String documentId,
                String fileName,
                int chunkCount,
                String message) {
}
