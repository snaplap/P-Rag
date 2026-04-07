package com.zzp.rag.service;

import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.dto.QueryRequest;
import com.zzp.rag.domain.dto.RagAnswer;
import com.zzp.rag.domain.dto.RagEvaluation;
import com.zzp.rag.domain.dto.WebSearchResult;
import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.model.RetrievalChunk;
import com.zzp.rag.service.audit.QaAuditService;
import com.zzp.rag.service.audit.RagEvaluationService;
import com.zzp.rag.service.audit.RuntimeMetricsService;
import com.zzp.rag.service.cache.CacheService;
import com.zzp.rag.service.cache.SessionContextService;
import com.zzp.rag.service.cache.WebSearchVectorCacheService;
import com.zzp.rag.service.generation.AnswerGenerationService;
import com.zzp.rag.service.mcp.McpQueryPreprocessorService;
import com.zzp.rag.service.mcp.McpRoutingDecisionService;
import com.zzp.rag.service.mcp.McpToolClient;
import com.zzp.rag.service.mcp.MindMapPreprocessService;
import com.zzp.rag.service.rerank.RerankService;
import com.zzp.rag.service.retrieval.QueryRewriteService;
import com.zzp.rag.service.retrieval.RetrievalService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagOrchestratorServiceTest {

        @Test
        void shouldApplyRerankAfterHybridMerge() {
                RagProperties properties = new RagProperties();
                properties.getRetrieval().setDefaultTopK(2);
                properties.getRetrieval().setMinScore(0.45d);

                CacheService cacheService = mock(CacheService.class);
                SessionContextService sessionContextService = mock(SessionContextService.class);
                RetrievalService retrievalService = mock(RetrievalService.class);
                QueryRewriteService queryRewriteService = mock(QueryRewriteService.class);
                McpQueryPreprocessorService mcpQueryPreprocessorService = mock(McpQueryPreprocessorService.class);
                MindMapPreprocessService mindMapPreprocessService = mock(MindMapPreprocessService.class);
                McpToolClient mcpToolClient = mock(McpToolClient.class);
                McpRoutingDecisionService routingDecisionService = mock(McpRoutingDecisionService.class);
                WebSearchVectorCacheService webSearchVectorCacheService = mock(WebSearchVectorCacheService.class);
                RerankService rerankService = mock(RerankService.class);
                AnswerGenerationService answerGenerationService = mock(AnswerGenerationService.class);
                RagEvaluationService ragEvaluationService = mock(RagEvaluationService.class);
                QaAuditService qaAuditService = mock(QaAuditService.class);
                RuntimeMetricsService runtimeMetricsService = mock(RuntimeMetricsService.class);

                RagOrchestratorService service = new RagOrchestratorService(
                                properties,
                                cacheService,
                                sessionContextService,
                                retrievalService,
                                queryRewriteService,
                                mcpQueryPreprocessorService,
                                mindMapPreprocessService,
                                mcpToolClient,
                                routingDecisionService,
                                webSearchVectorCacheService,
                                rerankService,
                                answerGenerationService,
                                ragEvaluationService,
                                qaAuditService,
                                runtimeMetricsService);

                QueryRequest request = new QueryRequest();
                request.setQuestion("请综合知识库和互联网给出结论");
                request.setSessionId("s-1");
                request.setKnowledgeBaseId("kb-1");
                request.setTopK(2);
                request.setEnableMindMap(false);

                RetrievalChunk kb1 = new RetrievalChunk("kb-1", "kb-doc-1", "知识库证据1", 0.92d,
                                DataSourceType.KNOWLEDGE_BASE);
                RetrievalChunk kb2 = new RetrievalChunk("kb-2", "kb-doc-2", "知识库证据2", 0.71d,
                                DataSourceType.KNOWLEDGE_BASE);
                RetrievalChunk webAsChunk = new RetrievalChunk("web-0", "WebDoc", "WebDoc。联网证据", 0.83d,
                                DataSourceType.WEB);

                when(cacheService.get(anyString(), anyString())).thenReturn(Optional.empty());
                when(sessionContextService.load(anyString())).thenReturn(List.of());
                when(queryRewriteService.rewrite(anyString(), anyList())).thenReturn("改写后的检索问题");
                when(mcpQueryPreprocessorService.preprocess(anyString())).thenReturn(
                                new McpQueryPreprocessorService.ProcessedQuery(
                                                "改写后的检索问题",
                                                List.of("改写后的检索问题", "改写后的检索问题 关键信息")));
                when(mcpQueryPreprocessorService.normalizeUrl(anyString()))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(mindMapPreprocessService.preprocessEvidence(anyList()))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(mindMapPreprocessService.compressAnswer(anyString()))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(mindMapPreprocessService.buildEvidenceBindings(anyList())).thenReturn(List.of());
                when(retrievalService.retrieve(anyString(), anyString(), anyInt(), anyString()))
                                .thenReturn(List.of(kb1, kb2));
                when(routingDecisionService.requiresFreshSearch(anyString())).thenReturn(false);
                when(routingDecisionService.decide(anyString(), anyList(), anyDouble())).thenReturn(
                                new McpRoutingDecisionService.Decision(
                                                McpRoutingDecisionService.Route.RAG_MCP,
                                                0.63d,
                                                0.65d,
                                                true,
                                                "部分",
                                                false,
                                                java.util.Set.of("A", "D"),
                                                "hybrid"));

                when(mcpToolClient.searchWeb(anyString(), anyInt())).thenReturn(
                                List.of(new WebSearchResult("WebDoc", "https://example.com/doc", "联网证据", 0.83d)));

                when(rerankService.rerank(anyString(), anyList())).thenReturn(List.of(webAsChunk, kb2, kb1));
                when(answerGenerationService.generateAnswerWithDiagnostics(anyString(), anyList(), anyList(), any()))
                                .thenReturn(new AnswerGenerationService.GenerationOutcome("综合回答", true, null));
                when(ragEvaluationService.evaluate(any(), anyList(), anyString()))
                                .thenReturn(new RagEvaluation(true, "LOW", 0.9d, "ok"));

                RuntimeMetricsService.RequestTracker tracker = new RuntimeMetricsService.RequestTracker(
                                System.currentTimeMillis(),
                                System.nanoTime());
                when(runtimeMetricsService.startRequest()).thenReturn(tracker);
                when(runtimeMetricsService.buildLogMetrics(any(), anyString(), anyList(), anyList(), anyString(), any(),
                                anyLong(),
                                anyLong(), anyBoolean()))
                                .thenReturn(new LinkedHashMap<>());

                RagAnswer answer = service.answer(request);

                Assertions.assertEquals(DataSourceType.HYBRID, answer.dataSource());
                Assertions.assertEquals(2, answer.references().size());
                Assertions.assertEquals("WebDoc", answer.references().get(0).documentId());
                Assertions.assertEquals("kb-doc-2", answer.references().get(1).documentId());

                @SuppressWarnings("unchecked")
                ArgumentCaptor<List<RetrievalChunk>> rerankInputCaptor = (ArgumentCaptor<List<RetrievalChunk>>) (ArgumentCaptor<?>) ArgumentCaptor
                                .forClass(List.class);
                verify(rerankService, times(1)).rerank(anyString(), rerankInputCaptor.capture());
                Assertions.assertEquals(3, rerankInputCaptor.getValue().size());

                verify(queryRewriteService, times(1)).rewrite(eq("请综合知识库和互联网给出结论"), anyList());
                verify(mcpQueryPreprocessorService, times(1)).preprocess(eq("改写后的检索问题"));
                verify(retrievalService, times(1)).retrieve(
                                eq("请综合知识库和互联网给出结论"),
                                eq("改写后的检索问题"),
                                eq(2),
                                eq("kb-1"));
                verify(mcpToolClient, times(2)).searchWeb(anyString(), eq(2));

                verify(mcpToolClient, never()).generateMindMap(anyString(), anyString(), any(), anyList());
        }
}
