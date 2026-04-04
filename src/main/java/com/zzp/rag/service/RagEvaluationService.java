package com.zzp.rag.service;

import com.zzp.rag.domain.DataSourceType;
import com.zzp.rag.domain.RagEvaluation;
import com.zzp.rag.domain.RetrievalChunk;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagEvaluationService {

    public RagEvaluation evaluate(DataSourceType sourceType, List<RetrievalChunk> evidence, String answer) {
        int evidenceCount = evidence == null ? 0 : evidence.size();
        boolean knowledgeHit = (sourceType == DataSourceType.KNOWLEDGE_BASE || sourceType == DataSourceType.HYBRID)
                && evidenceCount > 0;
        String risk;
        if (evidenceCount == 0) {
            risk = "HIGH";
        } else if (evidenceCount == 1) {
            risk = "MEDIUM";
        } else {
            risk = "LOW";
        }

        double traceabilityScore = Math.min(1.0d, evidenceCount / 3.0d);
        String note;
        if (evidenceCount == 0) {
            note = "未找到可追溯证据，存在较高幻觉风险。";
        } else if (sourceType == DataSourceType.WEB) {
            note = "回答基于联网检索结果，请在生产环境做来源可信度校验。";
        } else if (sourceType == DataSourceType.HYBRID) {
            note = "回答综合了知识库与联网检索证据，请重点核对外部来源时效性。";
        } else {
            note = "回答可回溯到知识库检索片段。";
        }

        return new RagEvaluation(knowledgeHit, risk, traceabilityScore, note);
    }
}
