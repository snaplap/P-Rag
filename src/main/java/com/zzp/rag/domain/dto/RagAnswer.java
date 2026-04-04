package com.zzp.rag.domain.dto;

import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.model.RetrievalChunk;

import java.util.List;
import java.util.Map;

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
