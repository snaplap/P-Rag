package com.zzp.rag.service.retrieval;

import com.zzp.rag.domain.model.RetrievalChunk;
import com.zzp.rag.service.embedding.EmbeddingService;
import com.zzp.rag.service.rerank.RerankService;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class RetrievalService {

    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final RerankService rerankService;

    public RetrievalService(EmbeddingService embeddingService, VectorStore vectorStore, RerankService rerankService) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.rerankService = rerankService;
    }

    public List<RetrievalChunk> retrieve(String question, int topK, String knowledgeBaseId) {
        int safeTopK = Math.max(1, topK);
        double[] queryVector = embeddingService.embed(question);
        // 先扩大候选池，再执行模型重排，提升召回稳定性。
        List<RetrievalChunk> candidates = vectorStore.search(queryVector, Math.max(12, safeTopK * 3), knowledgeBaseId);

        try {
            List<RetrievalChunk> reranked = rerankService.rerank(question, candidates);
            return reranked.stream()
                    .sorted(Comparator.comparingDouble(RetrievalChunk::score).reversed())
                    .limit(safeTopK)
                    .toList();
        } catch (RuntimeException ex) {
            return candidates.stream()
                    .sorted(Comparator.comparingDouble(RetrievalChunk::score).reversed())
                    .limit(safeTopK)
                    .toList();
        }
    }
}
