package com.zzp.rag.service.retrieval;

import com.zzp.rag.domain.model.RetrievalChunk;
import com.zzp.rag.domain.model.VectorDocument;

import java.util.List;

/**
 * 向量存储抽象接口。
 */
public interface VectorStore {

    /**
     * 写入或更新向量文档。
     */
    void upsert(VectorDocument vectorDocument);

    /**
     * 按查询向量和知识库检索候选。
     */
    List<RetrievalChunk> search(double[] queryVector, int topK, String knowledgeBaseId);

    /**
     * 按知识库删除向量数据。
     */
    int deleteByKnowledgeBaseId(String knowledgeBaseId);
}
