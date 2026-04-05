package com.zzp.rag.domain.dto;

/**
 * 回答质量评估结果。
 */
public record RagEvaluation(
                boolean knowledgeHit,
                String hallucinationRisk,
                double traceabilityScore,
                String note) {
}
