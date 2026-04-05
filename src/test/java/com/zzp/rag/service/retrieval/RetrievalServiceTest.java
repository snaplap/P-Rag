package com.zzp.rag.service.retrieval;

import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.model.RetrievalChunk;
import com.zzp.rag.service.embedding.EmbeddingService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class RetrievalServiceTest {

    @Test
    void shouldApplyBalancedFilteringAndDeduplicateChunks() {
        EmbeddingService embeddingService = text -> new double[] { 0.1d, 0.2d };
        VectorStore vectorStore = new VectorStore() {
            @Override
            public void upsert(com.zzp.rag.domain.model.VectorDocument vectorDocument) {
            }

            @Override
            public List<RetrievalChunk> search(double[] queryVector, int topK, String knowledgeBaseId) {
                return List.of(
                        new RetrievalChunk("1", "doc-a", "RAG评估包含可追溯性与命中率", 0.92d, DataSourceType.KNOWLEDGE_BASE),
                        new RetrievalChunk("2", "doc-a", "RAG评估包含可追溯性与命中率", 0.88d, DataSourceType.KNOWLEDGE_BASE),
                        new RetrievalChunk("3", "doc-b", "回答质量可通过高质量证据占比衡量", 0.53d, DataSourceType.KNOWLEDGE_BASE),
                        new RetrievalChunk("4", "doc-c", "随机噪声片段", 0.31d, DataSourceType.KNOWLEDGE_BASE));
            }

            @Override
            public int deleteByKnowledgeBaseId(String knowledgeBaseId) {
                return 0;
            }
        };
        RetrievalService service = new RetrievalService(embeddingService, vectorStore);
        List<RetrievalChunk> result = service.retrieve("如何评估RAG", 3, "kb-1");

        Assertions.assertEquals(2, result.size());
        Assertions.assertTrue(result.stream().noneMatch(v -> v.score() < 0.4d));
        long duplicateCount = result.stream().filter(v -> "doc-a".equals(v.documentId())).count();
        Assertions.assertEquals(1L, duplicateCount);
    }

    @Test
    void shouldReturnVectorCandidatesWithoutRerankDependency() {
        EmbeddingService embeddingService = text -> new double[] { 0.1d };
        VectorStore vectorStore = new VectorStore() {
            @Override
            public void upsert(com.zzp.rag.domain.model.VectorDocument vectorDocument) {
            }

            @Override
            public List<RetrievalChunk> search(double[] queryVector, int topK, String knowledgeBaseId) {
                return List.of(new RetrievalChunk(
                        "1",
                        "doc-a",
                        "核心结论：先做证据过滤再回答。",
                        0.79d,
                        DataSourceType.KNOWLEDGE_BASE));
            }

            @Override
            public int deleteByKnowledgeBaseId(String knowledgeBaseId) {
                return 0;
            }
        };
        RetrievalService service = new RetrievalService(embeddingService, vectorStore);
        List<RetrievalChunk> result = service.retrieve("给出建议", 1, "kb-1");

        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals("doc-a", result.get(0).documentId());
    }
}
