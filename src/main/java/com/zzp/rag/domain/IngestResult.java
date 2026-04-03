package com.zzp.rag.domain;

public record IngestResult(
        String documentId,
        int chunkCount,
        String message) {
}
