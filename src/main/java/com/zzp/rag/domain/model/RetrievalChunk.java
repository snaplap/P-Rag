package com.zzp.rag.domain.model;

/**
 * 检索到的证据片段。
 */
public record RetrievalChunk(
                String id,
                String documentId,
                String content,
                double score,
                DataSourceType sourceType) {
}
