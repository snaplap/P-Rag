package com.zzp.rag.service.chunking;

import com.zzp.rag.domain.dto.IngestResult;
import com.zzp.rag.domain.model.VectorDocument;
import com.zzp.rag.service.embedding.EmbeddingService;
import com.zzp.rag.service.retrieval.VectorStore;
import com.zzp.rag.service.trace.KnowledgeTraceService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class MarkdownIngestionService {

    private final MarkdownChunker markdownChunker;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final KnowledgeTraceService knowledgeTraceService;

    public MarkdownIngestionService(MarkdownChunker markdownChunker, EmbeddingService embeddingService,
            VectorStore vectorStore,
            KnowledgeTraceService knowledgeTraceService) {
        this.markdownChunker = markdownChunker;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.knowledgeTraceService = knowledgeTraceService;
    }

    /**
     * 将 Markdown 文档切片、向量化并写入向量存储。
     * 注意：knowledgeBaseId 与 sessionId 在这里绑定生成，后续检索与会话追踪都依赖该约定。
     */
    public IngestResult ingestMarkdown(String markdown, String documentId, String fileName) {
        String knowledgeBaseId = "kb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String sessionId = "session-" + knowledgeBaseId;
        String docId = (documentId == null || documentId.isBlank()) ? UUID.randomUUID().toString() : documentId;
        List<String> chunks = markdownChunker.split(markdown);

        // 逐片段向量化并写入存储，保证后续可以按片段召回证据。
        int index = 0;
        for (String chunk : chunks) {
            String chunkId = docId + "#" + index;
            double[] vector = embeddingService.embed(chunk);
            vectorStore.upsert(new VectorDocument(chunkId, knowledgeBaseId, docId, chunk, vector));
            index++;
        }

        String safeFileName = (fileName == null || fileName.isBlank()) ? "untitled.md" : fileName;
        knowledgeTraceService.save(knowledgeBaseId, sessionId, docId, safeFileName, chunks.size());
        return new IngestResult(knowledgeBaseId, sessionId, docId, safeFileName, chunks.size(),
                "markdown ingestion completed");
    }
}
