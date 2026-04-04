package com.zzp.rag.service;

import com.zzp.rag.domain.DataSourceType;
import com.zzp.rag.domain.RagEvaluation;
import com.zzp.rag.domain.RetrievalChunk;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class RagEvaluationServiceTest {

    private final RagEvaluationService ragEvaluationService = new RagEvaluationService();

    @Test
    void shouldReturnHighRiskWhenNoEvidence() {
        RagEvaluation evaluation = ragEvaluationService.evaluate(DataSourceType.WEB, List.of(), "answer");

        Assertions.assertEquals("HIGH", evaluation.hallucinationRisk());
        Assertions.assertEquals(0.0d, evaluation.traceabilityScore());
    }

    @Test
    void shouldReturnLowRiskWhenMultipleEvidence() {
        List<RetrievalChunk> evidence = List.of(
                new RetrievalChunk("1", "doc", "a", 0.9d, DataSourceType.KNOWLEDGE_BASE),
                new RetrievalChunk("2", "doc", "b", 0.8d, DataSourceType.KNOWLEDGE_BASE));

        RagEvaluation evaluation = ragEvaluationService.evaluate(DataSourceType.KNOWLEDGE_BASE, evidence, "answer");

        Assertions.assertEquals("LOW", evaluation.hallucinationRisk());
        Assertions.assertTrue(evaluation.knowledgeHit());
    }

    @Test
    void shouldMarkKnowledgeHitForHybridSource() {
        List<RetrievalChunk> evidence = List.of(
                new RetrievalChunk("1", "doc", "a", 0.88d, DataSourceType.KNOWLEDGE_BASE),
                new RetrievalChunk("web-1", "https://example.com", "b", 0.76d, DataSourceType.WEB));

        RagEvaluation evaluation = ragEvaluationService.evaluate(DataSourceType.HYBRID, evidence, "answer");

        Assertions.assertTrue(evaluation.knowledgeHit());
        Assertions.assertEquals("LOW", evaluation.hallucinationRisk());
        Assertions.assertTrue(evaluation.note().contains("综合了知识库与联网检索证据"));
    }
}
