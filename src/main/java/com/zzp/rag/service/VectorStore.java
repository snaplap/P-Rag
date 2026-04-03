package com.zzp.rag.service;

import com.zzp.rag.domain.RetrievalChunk;
import com.zzp.rag.domain.VectorDocument;

import java.util.List;

public interface VectorStore {
    void upsert(VectorDocument vectorDocument);

    List<RetrievalChunk> search(double[] queryVector, int topK, String knowledgeBaseId);
}
