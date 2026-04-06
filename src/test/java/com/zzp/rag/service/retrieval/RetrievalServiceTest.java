package com.zzp.rag.service.retrieval;

import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.model.RetrievalChunk;
import com.zzp.rag.service.embedding.EmbeddingService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void shouldUseDualRouteRetrievalAndFuseCandidates() {
        AtomicReference<String> embeddedQuery = new AtomicReference<>("");
        EmbeddingService embeddingService = text -> {
            embeddedQuery.set(text);
            return new double[] { 0.1d };
        };

        AtomicInteger searchCalls = new AtomicInteger(0);
        AtomicInteger keywordSearchCalls = new AtomicInteger(0);
        VectorStore vectorStore = new VectorStore() {
            @Override
            public void upsert(com.zzp.rag.domain.model.VectorDocument vectorDocument) {
            }

            @Override
            public List<RetrievalChunk> search(double[] queryVector, int topK, String knowledgeBaseId) {
                searchCalls.incrementAndGet();
                return List.of(
                        new RetrievalChunk("s1", "doc-semantic", "多路召回可以提升覆盖率", 0.84d,
                                DataSourceType.KNOWLEDGE_BASE),
                        new RetrievalChunk("s2", "doc-shared", "向量召回需要重排", 0.73d,
                                DataSourceType.KNOWLEDGE_BASE));
            }

            @Override
            public List<RetrievalChunk> searchByKeywords(String query, int topK, String knowledgeBaseId) {
                keywordSearchCalls.incrementAndGet();
                return List.of(
                        new RetrievalChunk("k1", "doc-key", "Milvus 2.4 支持混合检索", 0.86d,
                                DataSourceType.KNOWLEDGE_BASE),
                        new RetrievalChunk("k2", "doc-shared", "向量召回需要重排", 0.70d,
                                DataSourceType.KNOWLEDGE_BASE));
            }

            @Override
            public int deleteByKnowledgeBaseId(String knowledgeBaseId) {
                return 0;
            }
        };

        RetrievalService service = new RetrievalService(embeddingService, vectorStore);
        List<RetrievalChunk> result = service.retrieve(
                "Milvus 2.4 混合检索怎么做",
                "Milvus 2.4 混合检索 实现策略",
                3,
                "kb-1");

        Assertions.assertEquals(1, searchCalls.get());
        Assertions.assertEquals(1, keywordSearchCalls.get());
        Assertions.assertEquals("Milvus 2.4 混合检索 实现策略", embeddedQuery.get());
        Assertions.assertTrue(result.stream().anyMatch(v -> "doc-semantic".equals(v.documentId())));
        Assertions.assertTrue(result.stream().anyMatch(v -> "doc-key".equals(v.documentId())));
        Assertions.assertTrue(result.stream().anyMatch(v -> "doc-shared".equals(v.documentId())));
    }
}
