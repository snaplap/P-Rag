package com.zzp.rag.service.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.trace.ConversationTurn;
import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.model.RetrievalChunk;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
        Assertions.assertFalse(outcome.answer().contains("知识库"));
        Assertions.assertFalse(outcome.answer().contains("检索"));
        Assertions.assertFalse(outcome.answer().contains("证据"));
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
        Assertions.assertFalse(outcome.answer().contains("可参考来源"));
        Assertions.assertFalse(outcome.answer().contains("参考资料"));
        }

        @Test
        void shouldStripMechanismDescriptionsFromFallbackAnswer() {
        RagProperties properties = new RagProperties();
        properties.getLlm().setApiKey("");

        AnswerGenerationService service = new AnswerGenerationService(properties, new ObjectMapper());

        AnswerGenerationService.GenerationOutcome outcome = service.generateAnswerWithDiagnostics(
            "请给我结论",
            List.of(new ConversationTurn("上一轮问题", "上一轮回答", Instant.now())),
            List.of(new RetrievalChunk(
                "web-1",
                "MockSearch-1",
                "以上结论综合自主流技术分析（MockSearch-1/2/3），但具体案例或实证数据未在证据中提供。",
                0.72d,
                DataSourceType.WEB)),
            DataSourceType.WEB);

        Assertions.assertFalse(outcome.answer().contains("MockSearch"));
        Assertions.assertFalse(outcome.answer().contains("以上结论综合"));
        Assertions.assertFalse(outcome.answer().contains("证据中未提供"));
    }
}
