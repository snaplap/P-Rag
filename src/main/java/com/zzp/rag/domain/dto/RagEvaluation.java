package com.zzp.rag.domain.dto;

public record RagEvaluation(
        boolean knowledgeHit,
        String hallucinationRisk,
        double traceabilityScore,
        String note) {
}


