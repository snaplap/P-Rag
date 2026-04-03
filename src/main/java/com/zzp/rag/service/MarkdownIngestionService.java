package com.zzp.rag.service;

import com.zzp.rag.domain.IngestResult;
import com.zzp.rag.domain.VectorDocument;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class MarkdownIngestionService {

    private final MarkdownChunker markdownChunker;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;

    public MarkdownIngestionService(MarkdownChunker markdownChunker, EmbeddingService embeddingService,
            VectorStore vectorStore) {
        this.markdownChunker = markdownChunker;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
    }

    public IngestResult ingestMarkdown(String markdown, String documentId) {
        String docId = (documentId == null || documentId.isBlank()) ? UUID.randomUUID().toString() : documentId;
        List<String> chunks = markdownChunker.split(markdown);

        // 分片后逐条向量化并写入向量存储，便于后续语义检索。
        int index = 0;
        for (String chunk : chunks) {
            String chunkId = docId + "#" + index;
            double[] vector = embeddingService.embed(chunk);
            vectorStore.upsert(new VectorDocument(chunkId, docId, chunk, vector));
            index++;
        }

        return new IngestResult(docId, chunks.size(), "markdown ingestion completed");
    }
}
