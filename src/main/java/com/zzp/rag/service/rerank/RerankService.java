package com.zzp.rag.service.rerank;

import com.zzp.rag.domain.model.RetrievalChunk;

import java.util.List;

public interface RerankService {
    List<RetrievalChunk> rerank(String question, List<RetrievalChunk> candidates);
}
