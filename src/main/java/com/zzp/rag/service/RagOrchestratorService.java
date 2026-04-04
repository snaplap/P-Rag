package com.zzp.rag.service;

import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.trace.ConversationTurn;
import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.dto.MindMapCommand;
import com.zzp.rag.domain.dto.QueryRequest;
import com.zzp.rag.domain.dto.RagAnswer;
import com.zzp.rag.domain.dto.RagEvaluation;
import com.zzp.rag.domain.model.RetrievalChunk;
import com.zzp.rag.domain.dto.WebSearchResult;
import com.zzp.rag.service.audit.QaAuditService;
import com.zzp.rag.service.audit.RagEvaluationService;
import com.zzp.rag.service.audit.RuntimeMetricsService;
import com.zzp.rag.service.cache.CacheService;
import com.zzp.rag.service.cache.SessionContextService;
import com.zzp.rag.service.cache.WebSearchVectorCacheService;
import com.zzp.rag.service.generation.AnswerGenerationService;
import com.zzp.rag.service.mcp.McpRoutingDecisionService;
import com.zzp.rag.service.mcp.McpToolClient;
import com.zzp.rag.service.retrieval.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class RagOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(RagOrchestratorService.class);

    private final RagProperties ragProperties;
    private final CacheService cacheService;
    private final SessionContextService sessionContextService;
    private final RetrievalService retrievalService;
    private final McpToolClient mcpToolClient;
    private final McpRoutingDecisionService mcpRoutingDecisionService;
    private final WebSearchVectorCacheService webSearchVectorCacheService;
    private final AnswerGenerationService answerGenerationService;
    private final RagEvaluationService ragEvaluationService;
    private final QaAuditService qaAuditService;
    private final RuntimeMetricsService runtimeMetricsService;

    public RagOrchestratorService(
            RagProperties ragProperties,
            CacheService cacheService,
            SessionContextService sessionContextService,
            RetrievalService retrievalService,
            McpToolClient mcpToolClient,
            McpRoutingDecisionService mcpRoutingDecisionService,
            WebSearchVectorCacheService webSearchVectorCacheService,
            AnswerGenerationService answerGenerationService,
            RagEvaluationService ragEvaluationService,
            QaAuditService qaAuditService,
            RuntimeMetricsService runtimeMetricsService) {
        this.ragProperties = ragProperties;
        this.cacheService = cacheService;
        this.sessionContextService = sessionContextService;
        this.retrievalService = retrievalService;
        this.mcpToolClient = mcpToolClient;
        this.mcpRoutingDecisionService = mcpRoutingDecisionService;
        this.webSearchVectorCacheService = webSearchVectorCacheService;
        this.answerGenerationService = answerGenerationService;
        this.ragEvaluationService = ragEvaluationService;
        this.qaAuditService = qaAuditService;
        this.runtimeMetricsService = runtimeMetricsService;
    }

    public RagAnswer answer(QueryRequest request) {
        RuntimeMetricsService.RequestTracker tracker = runtimeMetricsService.startRequest();
        String question = request.getQuestion().trim();
        String sessionId = normalizeSessionId(request.getSessionId());
        String knowledgeBaseId = normalizeKnowledgeBaseId(request.getKnowledgeBaseId());
        boolean enableMindMap = Boolean.TRUE.equals(request.getEnableMindMap());
        int topK = request.getTopK() == null
                ? ragProperties.getRetrieval().getDefaultTopK()
                : Math.max(1, request.getTopK());
        boolean forceFresh = mcpRoutingDecisionService.requiresFreshSearch(question);
        String cacheQuestion = buildCacheQuestion(question);

        try {
            // 绗竴姝ワ細缂撳瓨浼樺厛锛屽懡涓洿鎺ヨ繑鍥炪€?
            Optional<RagAnswer> cacheHit = forceFresh ? Optional.empty()
                    : cacheService.get(cacheQuestion, knowledgeBaseId);
            if (!forceFresh && cacheHit.isPresent()) {
                RagAnswer cached = cacheHit.get();
                boolean lowValueCache = cached.uncertain()
                        || cached.references() == null
                        || cached.references().isEmpty();
                if (lowValueCache) {
                    log.info("Skip low-value cache for question={}, knowledgeBaseId={}", question, knowledgeBaseId);
                } else {
                    MindMapCommand mindMapCommand = null;
                    if (enableMindMap) {
                        mindMapCommand = cached.mindMapCommand();
                        if (mindMapCommand == null) {
                            mindMapCommand = mcpToolClient.generateMindMap(
                                    question,
                                    cached.answer(),
                                    cached.dataSource(),
                                    cached.references());
                        }
                    }

                    Map<String, Object> logMetrics = runtimeMetricsService.buildLogMetrics(
                            tracker,
                            question,
                            List.of(),
                            cached.references(),
                            cached.answer(),
                            cached.dataSource(),
                            0L,
                            0L,
                            true);

                    RagAnswer answer = new RagAnswer(
                            cached.question(),
                            cached.answer(),
                            cached.dataSource(),
                            cached.uncertain(),
                            true,
                            cached.references(),
                            cached.evaluation(),
                            logMetrics,
                            mindMapCommand);
                    qaAuditService.safeInsert(sessionId, question, answer.dataSource(), answer.uncertain(), true);
                    return answer;
                }
            }

            long retrievalStart = System.nanoTime();
            List<RetrievalChunk> kbEvidence = retrievalService.retrieve(question, topK, knowledgeBaseId);
            McpRoutingDecisionService.Decision decision = mcpRoutingDecisionService.decide(
                    question,
                    kbEvidence,
                    ragProperties.getRetrieval().getMinScore());
            log.info("MCP routing decision: {}", decision.reason());

            List<ConversationTurn> history = sessionContextService.load(sessionId);
            List<RetrievalChunk> evidence;
            DataSourceType sourceType;

            if (decision.route() == McpRoutingDecisionService.Route.MCP) {
                List<RetrievalChunk> webEvidence = fallbackToWebSearch(question, topK);
                webSearchVectorCacheService.cache(question, webEvidence);
                evidence = webEvidence.isEmpty() ? kbEvidence : webEvidence;
                sourceType = webEvidence.isEmpty() ? DataSourceType.KNOWLEDGE_BASE : DataSourceType.WEB;
            } else if (decision.route() == McpRoutingDecisionService.Route.RAG_MCP) {
                List<RetrievalChunk> webEvidence = fallbackToWebSearch(question, topK);
                webSearchVectorCacheService.cache(question, webEvidence);
                evidence = mergeEvidence(kbEvidence, webEvidence, topK);
                sourceType = webEvidence.isEmpty() ? DataSourceType.KNOWLEDGE_BASE : DataSourceType.HYBRID;
            } else {
                evidence = kbEvidence;
                sourceType = DataSourceType.KNOWLEDGE_BASE;
            }
            long retrievalMs = Math.max(0L, (System.nanoTime() - retrievalStart) / 1_000_000L);

            long generationStart = System.nanoTime();
            String answerText = answerGenerationService.generateAnswer(question, history, evidence, sourceType);
            long generationMs = Math.max(0L, (System.nanoTime() - generationStart) / 1_000_000L);

            RagEvaluation evaluation = ragEvaluationService.evaluate(sourceType, evidence, answerText);
            boolean uncertain = "HIGH".equalsIgnoreCase(evaluation.hallucinationRisk()) || evidence.isEmpty();
            if (uncertain) {
                answerText = answerText + "\n涓嶇‘瀹氭€у０鏄庯細褰撳墠璇佹嵁涓嶈冻锛岀粨璁轰粎渚涘弬鑰冦€俓n";
            }

            MindMapCommand mindMapCommand = null;
            if (enableMindMap) {
                mindMapCommand = mcpToolClient.generateMindMap(question, answerText, sourceType, evidence);
            }

            Map<String, Object> logMetrics = runtimeMetricsService.buildLogMetrics(
                    tracker,
                    question,
                    history,
                    evidence,
                    answerText,
                    sourceType,
                    retrievalMs,
                    generationMs,
                    false);

            RagAnswer ragAnswer = new RagAnswer(
                    question,
                    answerText,
                    sourceType,
                    uncertain,
                    false,
                    evidence,
                    evaluation,
                    logMetrics,
                    mindMapCommand);

            // 绗洓姝ワ細鍐欑紦瀛樸€佸啓浼氳瘽璁板繂銆佸啓瀹¤銆?
            if (!uncertain && evidence != null && !evidence.isEmpty()) {
                cacheService.put(cacheQuestion, knowledgeBaseId, ragAnswer);
            }
            sessionContextService.append(sessionId, new ConversationTurn(question, answerText, Instant.now()));
            qaAuditService.safeInsert(sessionId, question, sourceType, uncertain, false);
            return ragAnswer;
        } catch (RuntimeException ex) {
            runtimeMetricsService.markError();
            throw ex;
        }
    }

    private String buildCacheQuestion(String question) {
        return question + "::auto-route";
    }

    private List<RetrievalChunk> mergeEvidence(List<RetrievalChunk> kbEvidence, List<RetrievalChunk> webEvidence,
            int topK) {
        List<RetrievalChunk> merged = new ArrayList<>();
        if (kbEvidence != null && !kbEvidence.isEmpty()) {
            merged.addAll(kbEvidence);
        }
        if (webEvidence != null && !webEvidence.isEmpty()) {
            merged.addAll(webEvidence);
        }

        if (merged.isEmpty()) {
            return merged;
        }

        int limit = Math.max(topK, Math.min(8, merged.size()));
        if (merged.size() <= limit) {
            return merged;
        }
        return merged.subList(0, limit);
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
