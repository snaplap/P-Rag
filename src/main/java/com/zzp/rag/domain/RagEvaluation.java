package com.zzp.rag.domain;

public record RagEvaluation(
        boolean knowledgeHit,
        String hallucinationRisk,
        double traceabilityScore,
        String note) {
}
