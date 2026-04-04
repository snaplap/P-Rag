package com.zzp.rag.domain.dto;

public record IngestResult(
                String knowledgeBaseId,
                String sessionId,
                String documentId,
                String fileName,
                int chunkCount,
                String message) {
}


