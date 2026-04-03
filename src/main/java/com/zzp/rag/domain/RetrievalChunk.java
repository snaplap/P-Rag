package com.zzp.rag.domain;

public record RetrievalChunk(
        String id,
        String documentId,
        String content,
        double score,
        DataSourceType sourceType) {
}
