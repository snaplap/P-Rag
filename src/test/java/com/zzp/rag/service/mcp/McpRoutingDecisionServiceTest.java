package com.zzp.rag.service.mcp;

import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.model.RetrievalChunk;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class McpRoutingDecisionServiceTest {

    @Test
    void shouldPreferRagForColloquialDocumentSummaryQuestion() {
        McpRoutingDecisionService service = new McpRoutingDecisionService();

        List<RetrievalChunk> evidence = List.of(
                new RetrievalChunk(
                        "chunk-1",
                        "产品设计文档",
                        "文档主要讲了RAG链路、质量指标和回退策略。",
                        0.78d,
                        DataSourceType.KNOWLEDGE_BASE));

        McpRoutingDecisionService.Decision decision = service.decide("这篇到底讲了啥", evidence, 0.6d);

        Assertions.assertEquals(McpRoutingDecisionService.Route.RAG, decision.route());
    }
}
