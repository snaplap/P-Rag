package com.zzp.rag.service;

import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.ConversationTurn;
import com.zzp.rag.domain.DataSourceType;
import com.zzp.rag.domain.MindMapCommand;
import com.zzp.rag.domain.QueryRequest;
import com.zzp.rag.domain.RagAnswer;
import com.zzp.rag.domain.RagEvaluation;
import com.zzp.rag.domain.RetrievalChunk;
import com.zzp.rag.domain.WebSearchResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class RagOrchestratorService {

    private final RagProperties ragProperties;
    private final CacheService cacheService;
    private final SessionContextService sessionContextService;
    private final RetrievalService retrievalService;
    private final McpToolClient mcpToolClient;
    private final AnswerGenerationService answerGenerationService;
    private final RagEvaluationService ragEvaluationService;
    private final QaAuditService qaAuditService;

    public RagOrchestratorService(
            RagProperties ragProperties,
            CacheService cacheService,
            SessionContextService sessionContextService,
            RetrievalService retrievalService,
            McpToolClient mcpToolClient,
            AnswerGenerationService answerGenerationService,
            RagEvaluationService ragEvaluationService,
            QaAuditService qaAuditService) {
        this.ragProperties = ragProperties;
        this.cacheService = cacheService;
        this.sessionContextService = sessionContextService;
        this.retrievalService = retrievalService;
        this.mcpToolClient = mcpToolClient;
        this.answerGenerationService = answerGenerationService;
        this.ragEvaluationService = ragEvaluationService;
        this.qaAuditService = qaAuditService;
    }

    public RagAnswer answer(QueryRequest request) {
        String question = request.getQuestion().trim();
        String sessionId = normalizeSessionId(request.getSessionId());
        String knowledgeBaseId = normalizeKnowledgeBaseId(request.getKnowledgeBaseId());
        int topK = request.getTopK() == null
                ? ragProperties.getRetrieval().getDefaultTopK()
                : Math.max(1, request.getTopK());

        // 第一步：缓存优先，命中直接返回。
        Optional<RagAnswer> cacheHit = cacheService.get(question, knowledgeBaseId);
        if (cacheHit.isPresent()) {
            RagAnswer answer = cacheHit.get().markCacheHit();
            qaAuditService.safeInsert(sessionId, question, answer.dataSource(), answer.uncertain(), true);
            return answer;
        }

        // 第二步：基于必要历史进行向量检索与重排。
        List<ConversationTurn> history = sessionContextService.load(sessionId);
        List<RetrievalChunk> evidence = retrievalService.retrieve(question, topK, knowledgeBaseId);
        DataSourceType sourceType = DataSourceType.KNOWLEDGE_BASE;

        // 第三步：无命中或低分时走联网兜底。
        boolean canFallbackWeb = knowledgeBaseId == null;
        if ((evidence.isEmpty() || evidence.get(0).score() < ragProperties.getRetrieval().getMinScore())
                && canFallbackWeb) {
            evidence = fallbackToWebSearch(question, topK);
            sourceType = DataSourceType.WEB;
        }

        String answerText = answerGenerationService.generateAnswer(question, history, evidence, sourceType);
        RagEvaluation evaluation = ragEvaluationService.evaluate(sourceType, evidence, answerText);
        boolean uncertain = "HIGH".equalsIgnoreCase(evaluation.hallucinationRisk()) || evidence.isEmpty();
        if (uncertain) {
            answerText = answerText + "\n不确定性声明：当前证据不足，结论仅供参考。\n";
        }

        MindMapCommand mindMapCommand = mcpToolClient.generateMindMap(question, answerText, sourceType, evidence);

        RagAnswer ragAnswer = new RagAnswer(
                question,
                answerText,
                sourceType,
                uncertain,
                false,
                evidence,
                evaluation,
                mindMapCommand);

        // 第四步：写缓存、写会话记忆、写审计。
        cacheService.put(question, knowledgeBaseId, ragAnswer);
        sessionContextService.append(sessionId, new ConversationTurn(question, answerText, Instant.now()));
        qaAuditService.safeInsert(sessionId, question, sourceType, uncertain, false);
        return ragAnswer;
    }

    private List<RetrievalChunk> fallbackToWebSearch(String question, int topK) {
        List<WebSearchResult> webResults = mcpToolClient.searchWeb(question, topK);
        List<RetrievalChunk> chunks = new ArrayList<>();
        for (int i = 0; i < webResults.size(); i++) {
            WebSearchResult web = webResults.get(i);
            chunks.add(new RetrievalChunk(
                    "web-" + i,
                    web.url(),
                    web.snippet(),
                    web.confidence(),
                    DataSourceType.WEB));
        }
        return chunks;
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "anonymous";
        }
        return sessionId.trim();
    }

    private String normalizeKnowledgeBaseId(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return null;
        }
        return knowledgeBaseId.trim();
    }
}
