package com.zzp.rag.service.chunking;

import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.dto.IngestResult;
import com.zzp.rag.domain.model.VectorDocument;
import com.zzp.rag.service.cache.EmbeddingVectorCacheService;
import com.zzp.rag.service.embedding.EmbeddingService;
import com.zzp.rag.service.retrieval.VectorStore;
import com.zzp.rag.service.trace.KnowledgeTraceService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class MarkdownIngestionServiceTest {

    @Test
    void shouldBatchEmbedWithCacheMissAndStoreTitlePathContent() {
        RagProperties properties = new RagProperties();
        properties.getEmbedding().setBatchSize(20);

        MarkdownChunker chunker = mock(MarkdownChunker.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        EmbeddingVectorCacheService embeddingCache = mock(EmbeddingVectorCacheService.class);
        VectorStore vectorStore = mock(VectorStore.class);
        KnowledgeTraceService traceService = mock(KnowledgeTraceService.class);

        MarkdownIngestionService service = new MarkdownIngestionService(
                chunker,
                embeddingService,
                embeddingCache,
                vectorStore,
                traceService,
                properties);

        List<Chunk> chunks = List.of(
                new Chunk("正文A", "用户系统 > 登录功能", 0),
                new Chunk("正文B", "", 1));
        when(chunker.split(anyString())).thenReturn(chunks);

        String textA = "用户系统 > 登录功能\n正文A";
        String textB = "正文B";

        when(embeddingCache.getBatch(anyList())).thenReturn(Map.of(textB, new double[] { 0.2, 0.3 }));
        when(embeddingService.embedBatch(List.of(textA))).thenReturn(List.of(new double[] { 0.1, 0.2 }));

        IngestResult result = service.ingestMarkdown("mock", "doc-fixed", "demo.md");

        Assertions.assertEquals("doc-fixed", result.documentId());
        Assertions.assertEquals(2, result.chunkCount());

        verify(embeddingService, times(1)).embedBatch(List.of(textA));
        verify(embeddingService, never()).embed(anyString());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, double[]>> cachePutCaptor = (ArgumentCaptor<Map<String, double[]>>) (ArgumentCaptor<?>) ArgumentCaptor
                .forClass(Map.class);
        verify(embeddingCache, times(1)).putBatch(cachePutCaptor.capture());
        Assertions.assertTrue(cachePutCaptor.getValue().containsKey(textA));

        ArgumentCaptor<VectorDocument> upsertCaptor = ArgumentCaptor.forClass(VectorDocument.class);
        verify(vectorStore, times(2)).upsert(upsertCaptor.capture());
        List<VectorDocument> docs = upsertCaptor.getAllValues();

        Assertions.assertEquals("doc-fixed#0", docs.get(0).id());
        Assertions.assertEquals("用户系统 > 登录功能\n正文A", docs.get(0).content());

        Assertions.assertEquals("doc-fixed#1", docs.get(1).id());
        Assertions.assertEquals("正文B", docs.get(1).content());
    }

    @Test
    void shouldRespectConfiguredBatchSizeWhenPositive() {
        RagProperties properties = new RagProperties();
        properties.getEmbedding().setBatchSize(3);

        MarkdownChunker chunker = mock(MarkdownChunker.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        EmbeddingVectorCacheService embeddingCache = mock(EmbeddingVectorCacheService.class);
        VectorStore vectorStore = mock(VectorStore.class);
        KnowledgeTraceService traceService = mock(KnowledgeTraceService.class);

        MarkdownIngestionService service = new MarkdownIngestionService(
                chunker,
                embeddingService,
                embeddingCache,
                vectorStore,
                traceService,
                properties);

        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            chunks.add(new Chunk("正文-" + i, "", i));
        }
        when(chunker.split(anyString())).thenReturn(chunks);
        when(embeddingCache.getBatch(anyList())).thenReturn(Map.of());
        when(embeddingService.embedBatch(anyList())).thenAnswer(invocation -> {
            List<String> batch = invocation.getArgument(0);
            List<double[]> vectors = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                vectors.add(new double[] { i + 0.1, i + 0.2 });
            }
            return vectors;
        });

        service.ingestMarkdown("mock", "doc-batch", "batch.md");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> batchCaptor = (ArgumentCaptor<List<String>>) (ArgumentCaptor<?>) ArgumentCaptor
                .forClass(List.class);
        verify(embeddingService, times(4)).embedBatch(batchCaptor.capture());
        List<List<String>> batches = batchCaptor.getAllValues();

        Assertions.assertEquals(3, batches.get(0).size());
        Assertions.assertEquals(3, batches.get(1).size());
        Assertions.assertEquals(3, batches.get(2).size());
        Assertions.assertEquals(2, batches.get(3).size());

        verify(vectorStore, times(11)).upsert(org.mockito.ArgumentMatchers.any(VectorDocument.class));
    }

    @Test
    void shouldSkipEmbeddingWhenAllCacheHit() {
        RagProperties properties = new RagProperties();
        properties.getEmbedding().setBatchSize(20);

        MarkdownChunker chunker = mock(MarkdownChunker.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        EmbeddingVectorCacheService embeddingCache = mock(EmbeddingVectorCacheService.class);
        VectorStore vectorStore = mock(VectorStore.class);
        KnowledgeTraceService traceService = mock(KnowledgeTraceService.class);

        MarkdownIngestionService service = new MarkdownIngestionService(
                chunker,
                embeddingService,
                embeddingCache,
                vectorStore,
                traceService,
                properties);

        List<Chunk> chunks = List.of(
                new Chunk("正文A", "路径A", 0),
                new Chunk("正文B", "路径B", 1));
        when(chunker.split(anyString())).thenReturn(chunks);

        Map<String, double[]> allHit = new LinkedHashMap<>();
        allHit.put("路径A\n正文A", new double[] { 1.0, 1.0 });
        allHit.put("路径B\n正文B", new double[] { 2.0, 2.0 });
        when(embeddingCache.getBatch(anyList())).thenReturn(allHit);

        service.ingestMarkdown("mock", "doc-hit", "hit.md");

        verify(embeddingService, never()).embedBatch(anyList());
        verify(embeddingService, never()).embed(anyString());
        verify(vectorStore, times(2)).upsert(org.mockito.ArgumentMatchers.any(VectorDocument.class));
    }

    @Test
    void shouldFailFastWhenBatchEmbeddingThrows() {
        RagProperties properties = new RagProperties();
        properties.getEmbedding().setBatchSize(20);

        MarkdownChunker chunker = mock(MarkdownChunker.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        EmbeddingVectorCacheService embeddingCache = mock(EmbeddingVectorCacheService.class);
        VectorStore vectorStore = mock(VectorStore.class);
        KnowledgeTraceService traceService = mock(KnowledgeTraceService.class);

        MarkdownIngestionService service = new MarkdownIngestionService(
                chunker,
                embeddingService,
                embeddingCache,
                vectorStore,
                traceService,
                properties);

        when(chunker.split(anyString())).thenReturn(List.of(new Chunk("正文A", "路径A", 0)));
        when(embeddingCache.getBatch(anyList())).thenReturn(Map.of());
        doThrow(new IllegalStateException("batch failure")).when(embeddingService).embedBatch(anyList());

        IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class,
                () -> service.ingestMarkdown("mock", "doc-fail", "fail.md"));
        Assertions.assertTrue(ex.getMessage().contains("batch failure"));

        verify(embeddingService, never()).embed(anyString());
        verify(vectorStore, never()).upsert(org.mockito.ArgumentMatchers.any(VectorDocument.class));
    }
}
