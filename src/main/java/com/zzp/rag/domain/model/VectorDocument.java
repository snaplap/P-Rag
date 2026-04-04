package com.zzp.rag.domain.model;

public record VectorDocument(
                String id,
                String knowledgeBaseId,
                String documentId,
                String content,
                double[] vector) {
}


