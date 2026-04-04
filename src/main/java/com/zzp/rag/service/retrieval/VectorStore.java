package com.zzp.rag.service.retrieval;

import com.zzp.rag.domain.model.RetrievalChunk;
import com.zzp.rag.domain.model.VectorDocument;

import java.util.List;

public interface VectorStore {
    void upsert(VectorDocument vectorDocument);

    List<RetrievalChunk> search(double[] queryVector, int topK, String knowledgeBaseId);
}


