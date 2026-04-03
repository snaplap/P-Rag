package com.zzp.rag.domain;

import java.util.List;

public record RagAnswer(
        String question,
        String answer,
        DataSourceType dataSource,
        boolean uncertain,
        boolean cacheHit,
        List<RetrievalChunk> references,
        RagEvaluation evaluation,
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
                this.mindMapCommand);
    }
}
