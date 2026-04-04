package com.zzp.rag.service.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.model.DataSourceType;
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
        Assertions.assertTrue(outcome.answer().contains("根据当前可用内容"));
    }
}
