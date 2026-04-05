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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * RAG 主编排入口。
     * 注意：该方法按“缓存 -> 检索/路由 -> 生成 -> 评估 -> 持久化”顺序执行，
     * 任一阶段的策略变化都会影响最终回答质量与成本，调整时需配套回归测试。
     */
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
            // 第一步：缓存优先；命中且质量可用时直接返回。
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
                    attachChainDiagnostics(logMetrics, true, "CACHE_HIT", false, McpRoutingDecisionService.Route.RAG,
                            "served-from-cache");

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
            boolean mcpSearchFallback = false;

            if (decision.route() == McpRoutingDecisionService.Route.MCP) {
                WebEvidenceResult webEvidenceResult = fallbackToWebSearch(question, topK);
                List<RetrievalChunk> webEvidence = webEvidenceResult.chunks();
                mcpSearchFallback = webEvidenceResult.fallbackUsed();
                webSearchVectorCacheService.cache(question, webEvidence);
                evidence = webEvidence.isEmpty() ? kbEvidence : webEvidence;
                sourceType = webEvidence.isEmpty() ? DataSourceType.KNOWLEDGE_BASE : DataSourceType.WEB;
            } else if (decision.route() == McpRoutingDecisionService.Route.RAG_MCP) {
                WebEvidenceResult webEvidenceResult = fallbackToWebSearch(question, topK);
                List<RetrievalChunk> webEvidence = webEvidenceResult.chunks();
                mcpSearchFallback = webEvidenceResult.fallbackUsed();
                webSearchVectorCacheService.cache(question, webEvidence);
                evidence = mergeEvidence(kbEvidence, webEvidence, topK);
                sourceType = webEvidence.isEmpty() ? DataSourceType.KNOWLEDGE_BASE : DataSourceType.HYBRID;
            } else {
                evidence = kbEvidence;
                sourceType = DataSourceType.KNOWLEDGE_BASE;
            }
            long retrievalMs = Math.max(0L, (System.nanoTime() - retrievalStart) / 1_000_000L);

            long generationStart = System.nanoTime();
            AnswerGenerationService.GenerationOutcome generationOutcome = answerGenerationService
                    .generateAnswerWithDiagnostics(question, history, evidence, sourceType);
            String answerText = generationOutcome.answer();
            long generationMs = Math.max(0L, (System.nanoTime() - generationStart) / 1_000_000L);

            RagEvaluation evaluation = ragEvaluationService.evaluate(sourceType, evidence, answerText);
            boolean uncertain = "HIGH".equalsIgnoreCase(evaluation.hallucinationRisk()) || evidence.isEmpty();
            if (uncertain) {
                answerText = answerText + "\n不确定性声明：当前证据不足，结论仅供参考。\n";
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
            attachChainDiagnostics(
                    logMetrics,
                    generationOutcome.llmUsed(),
                    generationOutcome.fallbackReason(),
                    mcpSearchFallback,
                    decision.route(),
                    decision.reason());

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

            // 第四步：写缓存、会话记忆、审计日志。
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

    /**
     * 构建缓存问题键，包含自动路由标记，避免与其他缓存策略冲突。
     */
    private String buildCacheQuestion(String question) {
        return question + "::auto-route";
    }

    /**
     * 合并知识库与联网证据，统一排序并去重。
     * 注意：这里限制上限，防止过多低相关片段挤占上下文窗口。
     */
    private List<RetrievalChunk> mergeEvidence(List<RetrievalChunk> kbEvidence, List<RetrievalChunk> webEvidence,
            int topK) {
        List<RetrievalChunk> all = new ArrayList<>();
        if (kbEvidence != null) {
            all.addAll(kbEvidence);
        }
        if (webEvidence != null) {
            all.addAll(webEvidence);
        }
        if (all.isEmpty()) {
            return List.of();
        }

        List<RetrievalChunk> sorted = all.stream()
                .filter(v -> v != null && v.content() != null && !v.content().isBlank())
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .toList();
        if (sorted.isEmpty()) {
            return List.of();
        }

        int limit = Math.max(topK, Math.min(8, sorted.size()));
        List<RetrievalChunk> merged = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();
        for (RetrievalChunk chunk : sorted) {
            if (merged.size() >= limit) {
                break;
            }
            String key = evidenceKey(chunk);
            if (dedup.add(key)) {
                merged.add(chunk);
            }
        }
        return merged;
    }

    /**
     * 联网检索回退转换：将 Web 搜索结果转换为统一 RetrievalChunk。
     */
    private WebEvidenceResult fallbackToWebSearch(String question, int topK) {
        List<WebSearchResult> webResults = mcpToolClient.searchWeb(question, topK);
        List<RetrievalChunk> chunks = new ArrayList<>();
        for (int i = 0; i < webResults.size(); i++) {
            WebSearchResult web = webResults.get(i);
            String sourceLabel = toWebSourceLabel(web, i + 1);
            chunks.add(new RetrievalChunk(
                    "web-" + i,
                    sourceLabel,
                    buildWebChunkContent(web),
                    clampScore(web.confidence()),
                    DataSourceType.WEB));
        }
        return new WebEvidenceResult(chunks, isMockWebSearchResult(webResults));
    }

    /**
     * 生成用于检索/生成的 Web 证据正文。
     */
    private String buildWebChunkContent(WebSearchResult web) {
        if (web == null) {
            return "";
        }
        String title = web.title() == null ? "" : web.title().trim();
        String snippet = web.snippet() == null ? "" : web.snippet().trim();
        if (!title.isBlank() && !snippet.isBlank()) {
            return title + "。" + snippet;
        }
        return !snippet.isBlank() ? snippet : title;
    }

    /**
     * 将 Web 来源转成可读标签，优先标题，其次域名。
     */
    private String toWebSourceLabel(WebSearchResult web, int index) {
        if (web == null) {
            return "联网结果" + index;
        }
        String title = web.title() == null ? "" : web.title().trim();
        if (!title.isBlank()) {
            return title;
        }
        String url = web.url() == null ? "" : web.url().trim();
        if (url.startsWith("http://") || url.startsWith("https://")) {
            try {
                java.net.URI uri = java.net.URI.create(url);
                String host = uri.getHost();
                if (host != null && !host.isBlank()) {
                    return host.startsWith("www.") ? host.substring(4) : host;
                }
            } catch (Exception ignored) {
                // ignored
            }
        }
        return "联网结果" + index;
    }

    /**
     * 证据去重键：来源 + 内容前缀。
     */
    private String evidenceKey(RetrievalChunk chunk) {
        String source = chunk.documentId() == null ? "" : chunk.documentId().trim().toLowerCase(Locale.ROOT);
        String content = chunk.content() == null ? ""
                : chunk.content().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        if (content.length() > 100) {
            content = content.substring(0, 100);
        }
        return source + "|" + content;
    }

    /**
     * 分数裁剪到 [0,1]，避免异常分值污染后续排序。
     */
    private double clampScore(double score) {
        return Math.max(0.0d, Math.min(1.0d, score));
    }

    /**
     * 判断是否命中模拟 Web 结果。
     */
    private boolean isMockWebSearchResult(List<WebSearchResult> webResults) {
        if (webResults == null || webResults.isEmpty()) {
            return false;
        }
        return webResults.stream().allMatch(v -> {
            String url = v.url() == null ? "" : v.url();
            String snippet = v.snippet() == null ? "" : v.snippet();
            return url.contains("example.com/search") || snippet.contains("联网检索模拟结果");
        });
    }

    /**
     * 将链路诊断字段写入日志指标，便于排查“命中回退但看起来成功”的问题。
     */
    private void attachChainDiagnostics(
            Map<String, Object> logMetrics,
            boolean llmUsed,
            String llmFallbackReason,
            boolean mcpSearchFallback,
            McpRoutingDecisionService.Route route,
            String routeReason) {
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("LLM已使用", llmUsed ? "是" : "否");
        chain.put("LLM回退原因", llmFallbackReason == null || llmFallbackReason.isBlank() ? "-" : llmFallbackReason);
        chain.put("MCP检索回退", mcpSearchFallback ? "是" : "否");
        chain.put("MCP路由", route == null ? "-" : route.name());
        chain.put("MCP决策依据", routeReason == null || routeReason.isBlank() ? "-" : routeReason);
        logMetrics.put("链路诊断", chain);
    }

    /**
     * 归一化 sessionId。
     */
    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "anonymous";
        }
        return sessionId.trim();
    }

    /**
     * 归一化 knowledgeBaseId。
     */
    private String normalizeKnowledgeBaseId(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return null;
        }
        return knowledgeBaseId.trim();
    }

    private record WebEvidenceResult(List<RetrievalChunk> chunks, boolean fallbackUsed) {
    }
}
