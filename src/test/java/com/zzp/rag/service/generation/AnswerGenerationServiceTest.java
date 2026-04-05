package com.zzp.rag.service.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.model.RetrievalChunk;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class AnswerGenerationServiceTest {

    @Test
    void shouldReturnFallbackReasonWhenLlmNotConfigured() {
        RagProperties properties = new RagProperties();
        properties.getLlm().setApiKey("");

        AnswerGenerationService service = new AnswerGenerationService(properties, new ObjectMapper());

        AnswerGenerationService.GenerationOutcome outcome = service.generateAnswerWithDiagnostics(
                "测试问题",
                List.of(),
                List.of(),
                DataSourceType.KNOWLEDGE_BASE);

        Assertions.assertFalse(outcome.llmUsed());
        Assertions.assertEquals("LLM_NOT_CONFIGURED", outcome.fallbackReason());
        Assertions.assertTrue(outcome.answer().contains("知识库"));
    }

    @Test
    void shouldHideUuidLikeSourceInFallbackAnswer() {
        RagProperties properties = new RagProperties();
        properties.getLlm().setApiKey("");

        AnswerGenerationService service = new AnswerGenerationService(properties, new ObjectMapper());
        String uuid = "d9b2d63d-a233-4123-847a-3b6a8f5ad7a2";

        AnswerGenerationService.GenerationOutcome outcome = service.generateAnswerWithDiagnostics(
                "这篇文档到底讲了啥",
                List.of(),
                List.of(new RetrievalChunk(
                        "chunk-1",
                        uuid,
                        "本文主要介绍如何设计RAG评估指标，包括可追溯性、检索质量和回退率。",
                        0.82d,
                        DataSourceType.KNOWLEDGE_BASE)),
                DataSourceType.KNOWLEDGE_BASE);

        Assertions.assertFalse(outcome.llmUsed());
        Assertions.assertFalse(outcome.answer().contains(uuid));
        Assertions.assertTrue(outcome.answer().contains("知识库片段"));
    }
}
