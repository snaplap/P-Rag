package com.zzp.rag.domain;

public record VectorDocument(
                String id,
                String knowledgeBaseId,
                String documentId,
                String content,
                double[] vector) {
}
