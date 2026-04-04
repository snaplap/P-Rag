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

    public IngestResult ingestMarkdown(String markdown, String documentId, String fileName) {
        String knowledgeBaseId = "kb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String sessionId = "session-" + knowledgeBaseId;
        String docId = (documentId == null || documentId.isBlank()) ? UUID.randomUUID().toString() : documentId;
        List<String> chunks = markdownChunker.split(markdown);

        // 鍒嗙墖鍚庨€愭潯鍚戦噺鍖栧苟鍐欏叆鍚戦噺瀛樺偍锛屼究浜庡悗缁涔夋绱€?
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
