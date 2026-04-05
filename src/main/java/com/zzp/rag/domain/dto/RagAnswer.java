package com.zzp.rag.domain.dto;

import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.model.RetrievalChunk;

import java.util.List;
import java.util.Map;

/**
 * 问答结果对象，包含答案正文、证据和诊断信息。
 */
public record RagAnswer(
        String question,
        String answer,
        DataSourceType dataSource,
        boolean uncertain,
        boolean cacheHit,
        List<RetrievalChunk> references,
        RagEvaluation evaluation,
        Map<String, Object> logMetrics,
        MindMapCommand mindMapCommand) {

    /**
     * 返回一个标记为缓存命中的新对象。
     */
    public RagAnswer markCacheHit() {
        return new RagAnswer(
                this.question,
                this.answer,
                this.dataSource,
                this.uncertain,
                true,
                this.references,
                this.evaluation,
                this.logMetrics,
                this.mindMapCommand);
    }
}
