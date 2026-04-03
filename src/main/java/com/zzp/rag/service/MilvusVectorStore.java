package com.zzp.rag.service;

import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.DataSourceType;
import com.zzp.rag.domain.RetrievalChunk;
import com.zzp.rag.domain.VectorDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MilvusVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);

    private final RagProperties ragProperties;
    private final Map<String, VectorDocument> localIndex = new ConcurrentHashMap<>();

    public MilvusVectorStore(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @Override
    public void upsert(VectorDocument vectorDocument) {
        localIndex.put(vectorDocument.id(), vectorDocument);
        if (ragProperties.getMilvus().isUseRemote()) {
            pushToRemoteSafely(vectorDocument);
        }
    }

    @Override
    public List<RetrievalChunk> search(double[] queryVector, int topK) {
        List<RetrievalChunk> chunks = new ArrayList<>();
        for (VectorDocument doc : localIndex.values()) {
            double score = cosine(queryVector, doc.vector());
            chunks.add(new RetrievalChunk(
                    doc.id(),
                    doc.documentId(),
                    doc.content(),
                    score,
                    DataSourceType.KNOWLEDGE_BASE));
        }

        return chunks.stream()
                .sorted(Comparator.comparingDouble(RetrievalChunk::score).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    private void pushToRemoteSafely(VectorDocument vectorDocument) {
        // 为避免阻塞首版MVP，此处先保留远端Milvus扩展点，默认启用本地回退索引。
        log.debug(
                "Remote Milvus upsert placeholder called. collection={}, baseUrl={}, docId={}",
                ragProperties.getMilvus().getCollection(),
                ragProperties.getMilvus().getBaseUrl(),
                vectorDocument.id());
    }

    private double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0d;
        }

        double dot = 0.0d;
        double normA = 0.0d;
        double normB = 0.0d;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0d || normB == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
