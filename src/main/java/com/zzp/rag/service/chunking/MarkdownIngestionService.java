package com.zzp.rag.service.chunking;

import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.dto.IngestResult;
import com.zzp.rag.domain.model.VectorDocument;
import com.zzp.rag.service.cache.EmbeddingVectorCacheService;
import com.zzp.rag.service.embedding.EmbeddingService;
import com.zzp.rag.service.retrieval.VectorStore;
import com.zzp.rag.service.trace.KnowledgeTraceService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MarkdownIngestionService {

    private final MarkdownChunker markdownChunker;
    private final EmbeddingService embeddingService;
    private final EmbeddingVectorCacheService embeddingVectorCacheService;
    private final VectorStore vectorStore;
    private final KnowledgeTraceService knowledgeTraceService;
    private final RagProperties ragProperties;

    public MarkdownIngestionService(MarkdownChunker markdownChunker, EmbeddingService embeddingService,
            EmbeddingVectorCacheService embeddingVectorCacheService,
            VectorStore vectorStore,
            KnowledgeTraceService knowledgeTraceService,
            RagProperties ragProperties) {
        this.markdownChunker = markdownChunker;
        this.embeddingService = embeddingService;
        this.embeddingVectorCacheService = embeddingVectorCacheService;
        this.vectorStore = vectorStore;
        this.knowledgeTraceService = knowledgeTraceService;
        this.ragProperties = ragProperties;
    }

    /**
     * 将 Markdown 文档切片、向量化并写入向量存储。
     * 注意：knowledgeBaseId 与 sessionId 在这里绑定生成，后续检索与会话追踪都依赖该约定。
     */
    public IngestResult ingestMarkdown(String markdown, String documentId, String fileName) {
        String knowledgeBaseId = "kb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String sessionId = "session-" + knowledgeBaseId;
        String docId = (documentId == null || documentId.isBlank()) ? UUID.randomUUID().toString() : documentId;
        List<Chunk> chunks = markdownChunker.split(markdown);

        int batchSize = normalizeBatchSize(ragProperties.getEmbedding().getBatchSize());
        List<String> vectorTexts = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            vectorTexts.add(toStoredContent(chunk));
        }

        Map<String, double[]> vectorsByText = new LinkedHashMap<>(embeddingVectorCacheService.getBatch(vectorTexts));
        List<String> misses = collectMisses(vectorTexts, vectorsByText);
        if (!misses.isEmpty()) {
            Map<String, double[]> freshVectors = embedMissingTextsInBatch(misses, batchSize);
            vectorsByText.putAll(freshVectors);
            embeddingVectorCacheService.putBatch(freshVectors);
        }

        // 逐片段入库，保留标题路径上下文用于后续召回可读性。
        for (Chunk chunk : chunks) {
            String contentForStore = toStoredContent(chunk);
            double[] vector = vectorsByText.get(contentForStore);
            if (vector == null || vector.length == 0) {
                throw new IllegalStateException(
                        "Missing embedding vector for chunk index=" + chunk.chunkIndex() + ", documentId=" + docId);
            }

            String chunkId = docId + "#" + chunk.chunkIndex();
            vectorStore.upsert(new VectorDocument(chunkId, knowledgeBaseId, docId, contentForStore, vector));
        }

        String safeFileName = (fileName == null || fileName.isBlank()) ? "untitled.md" : fileName;
        knowledgeTraceService.save(knowledgeBaseId, sessionId, docId, safeFileName, chunks.size());
        return new IngestResult(knowledgeBaseId, sessionId, docId, safeFileName, chunks.size(),
                "markdown ingestion completed");
    }

    private Map<String, double[]> embedMissingTextsInBatch(List<String> missingTexts, int batchSize) {
        Map<String, double[]> result = new LinkedHashMap<>();
        for (int start = 0; start < missingTexts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, missingTexts.size());
            List<String> batch = missingTexts.subList(start, end);
            List<double[]> vectors = embeddingService.embedBatch(batch);
            if (vectors == null || vectors.size() != batch.size()) {
                throw new IllegalStateException(
                        "Batch embedding size mismatch in ingestion: expected=" + batch.size()
                                + ", actual=" + (vectors == null ? 0 : vectors.size()));
            }

            for (int i = 0; i < batch.size(); i++) {
                String text = batch.get(i);
                double[] vector = vectors.get(i);
                if (vector == null || vector.length == 0) {
                    throw new IllegalStateException("Batch embedding returned empty vector in ingestion: index=" + i);
                }
                result.put(text, vector);
            }
        }
        return result;
    }

    private List<String> collectMisses(List<String> texts, Map<String, double[]> vectorsByText) {
        LinkedHashSet<String> misses = new LinkedHashSet<>();
        for (String text : texts) {
            if (!vectorsByText.containsKey(text)) {
                misses.add(text);
            }
        }
        return new ArrayList<>(misses);
    }

    private int normalizeBatchSize(int configured) {
        if (configured <= 0) {
            return 10;
        }
        return Math.min(configured, 100);
    }

    private String toStoredContent(Chunk chunk) {
        String titlePath = chunk.titlePath() == null ? "" : chunk.titlePath().trim();
        if (titlePath.isBlank()) {
            return chunk.content();
        }
        return titlePath + "\n" + chunk.content();
    }
}
