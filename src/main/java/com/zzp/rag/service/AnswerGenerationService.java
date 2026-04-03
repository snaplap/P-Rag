package com.zzp.rag.service;

import com.zzp.rag.domain.ConversationTurn;
import com.zzp.rag.domain.DataSourceType;
import com.zzp.rag.domain.RetrievalChunk;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnswerGenerationService {

    public String generateAnswer(
            String question,
            List<ConversationTurn> history,
            List<RetrievalChunk> evidence,
            DataSourceType sourceType) {
        StringBuilder builder = new StringBuilder();
        builder.append("结论（仅基于已检索证据）：\n");

        if (evidence == null || evidence.isEmpty()) {
            builder.append("当前检索证据不足，无法确定答案。\n");
            builder.append("请补充更具体的问题或先上传相关知识文档。\n");
            builder.append("数据来源：").append(sourceLabel(sourceType)).append("\n");
            return builder.toString();
        }

        builder.append("问题：").append(question).append("\n");
        builder.append("证据要点：\n");

        int maxEvidence = Math.min(3, evidence.size());
        for (int i = 0; i < maxEvidence; i++) {
            RetrievalChunk chunk = evidence.get(i);
            builder.append(i + 1)
                    .append(". ")
                    .append(trimSnippet(chunk.content(), 160))
                    .append(" [score=")
                    .append(String.format("%.3f", chunk.score()))
                    .append("]\n");
        }

        if (history != null && !history.isEmpty()) {
            ConversationTurn latestTurn = history.get(history.size() - 1);
            builder.append("必要历史参考：").append(trimSnippet(latestTurn.question(), 80)).append("\n");
        }

        builder.append("数据来源：").append(sourceLabel(sourceType)).append("\n");
        builder.append("说明：答案严格基于上述证据，不包含未检索到的外部事实。\n");
        return builder.toString();
    }

    private String trimSnippet(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace("\n", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen) + "...";
    }

    private String sourceLabel(DataSourceType sourceType) {
        return sourceType == DataSourceType.KNOWLEDGE_BASE ? "知识库" : "联网";
    }
}
