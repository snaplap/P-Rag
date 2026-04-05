package com.zzp.rag.domain.model;

/**
 * 向量存储文档实体。
 */
public record VectorDocument(
        String id,
        String knowledgeBaseId,
        String documentId,
        String content,
        double[] vector) {
}
