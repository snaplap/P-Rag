package com.zzp.rag.service.mcp;

import com.zzp.rag.domain.model.RetrievalChunk;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class McpRoutingDecisionService {

    public enum Route {
        RAG,
        MCP,
        RAG_MCP
    }

    public record Decision(
            Route route,
            double confidence,
            double similarityScore,
            boolean keyHit,
            String completeness,
            boolean staleRisk,
            Set<String> categories,
            String reason) {
    }

    public Decision decide(String question, List<RetrievalChunk> ragEvidence, double minScore) {
        String q = question == null ? "" : question.trim();
        List<RetrievalChunk> safeEvidence = ragEvidence == null ? Collections.emptyList() : ragEvidence;
        Set<String> categories = classifyQuestion(q);

        boolean realtime = categories.contains("C");
        boolean externalWorld = categories.contains("D");
        boolean documentSummaryIntent = documentSummaryQuestion(q);

        double similarity = computeSimilarity(safeEvidence);
        boolean keyHit = !safeEvidence.isEmpty() && safeEvidence.get(0).score() >= minScore;
        String completeness = evaluateCompleteness(safeEvidence, similarity, keyHit);
        boolean staleRisk = realtime || mayBeOutdatedQuestion(q);

        double coverage = switch (completeness) {
            case "完整" -> 1.0d;
            case "部分" -> 0.65d;
            default -> 0.25d;
        };
        double timeliness = staleRisk ? 0.45d : 1.0d;
        double confidence = clamp((similarity * 0.5d) + (coverage * 0.3d) + (timeliness * 0.2d));

        Route route;
        if (realtime) {
            route = confidence < 0.5d ? Route.MCP : Route.RAG_MCP;
        } else if (confidence >= 0.8d) {
            route = Route.RAG;
        } else if (confidence < 0.5d) {
            route = Route.MCP;
        } else {
            route = Route.RAG_MCP;
        }

        boolean explicitLatest = explicitLatestIntent(q);
        boolean highRisk = highRiskDecisionQuestion(q) && (confidence < 0.8d || staleRisk);

        // 文档总结类问题在已有知识库证据时优先走RAG，避免无意义联网导致回答偏离上传文档。
        if (!realtime && documentSummaryIntent && !safeEvidence.isEmpty()) {
            route = Route.RAG;
        }

        // 成本优先：若RAG已足够回答，避免不必要MCP调用。
        if (route == Route.RAG_MCP
                && !externalWorld
                && !explicitLatest
                && keyHit
                && "完整".equals(completeness)
                && confidence >= 0.65d) {
            route = Route.RAG;
        }

        // 风险控制：不调用MCP会造成明显风险时，强制升级。
        if (route == Route.RAG && !documentSummaryIntent
                && (staleRisk || highRisk || (!keyHit && confidence < 0.8d))) {
            route = confidence < 0.5d ? Route.MCP : Route.RAG_MCP;
        }

        String reason = "route=" + route
                + ", conf=" + String.format(Locale.ROOT, "%.2f", confidence)
                + ", sim=" + String.format(Locale.ROOT, "%.2f", similarity)
                + ", keyHit=" + keyHit
                + ", complete=" + completeness
                + ", stale=" + staleRisk
                + ", docSummary=" + documentSummaryIntent
                + ", categories=" + categories;

        return new Decision(route, confidence, similarity, keyHit, completeness, staleRisk, categories, reason);
    }

    public boolean requiresFreshSearch(String question) {
        String q = question == null ? "" : question;
        Set<String> categories = classifyQuestion(q);
        return categories.contains("C") || explicitLatestIntent(q);
    }

    private Set<String> classifyQuestion(String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        Set<String> categories = new LinkedHashSet<>();

        if (containsAny(q, "公司", "内部", "文档", "faq", "流程", "制度", "知识库", "本系统", "本项目")) {
            categories.add("A");
        }
        if (containsAny(q, "是什么", "原理", "概念", "解释", "why", "how")) {
            categories.add("B");
        }
        if (containsAny(q, "最新", "当前", "今天", "实时", "now", "today", "新闻", "天气", "股价", "汇率", "政策")) {
            categories.add("C");
        }
        if (containsAny(q, "互联网", "外部", "行业", "趋势", "竞品", "市场", "产品", "官网")) {
            categories.add("D");
        }
        if (containsAny(q, "对比", "综合", "分别", "优缺点", "方案", "影响", "并且", "同时")) {
            categories.add("E");
        }

        if (categories.isEmpty()) {
            categories.add("B");
        }
        return categories;
    }

    private double computeSimilarity(List<RetrievalChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return 0.0d;
        }
        int limit = Math.min(3, evidence.size());
        double sum = 0.0d;
        for (int i = 0; i < limit; i++) {
            double score = evidence.get(i).score();
            sum += clamp(score);
        }
        return sum / limit;
    }

    private String evaluateCompleteness(List<RetrievalChunk> evidence, double similarity, boolean keyHit) {
        if (evidence == null || evidence.isEmpty()) {
            return "缺失";
        }
        if (keyHit && evidence.size() >= 2 && similarity >= 0.75d) {
            return "完整";
        }
        if (keyHit || similarity >= 0.5d) {
            return "部分";
        }
        return "缺失";
    }

    private boolean mayBeOutdatedQuestion(String q) {
        return containsAny(q, "版本", "发布", "更新时间", "政策", "价格", "活动", "行情", "趋势");
    }

    private boolean explicitLatestIntent(String q) {
        return containsAny(q.toLowerCase(Locale.ROOT), "最新", "当前", "today", "now", "刚刚", "实时");
    }

    private boolean highRiskDecisionQuestion(String q) {
        return containsAny(q.toLowerCase(Locale.ROOT), "投资", "买入", "医疗", "诊断", "法律", "合规", "财务", "风险", "决策");
    }

    private boolean documentSummaryQuestion(String q) {
        String lower = q.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "这篇文章", "这篇文档", "这份文档", "本文", "这篇材料", "这个文件", "该文", "文中",
                "讲了什么", "主要内容", "核心内容", "总结", "摘要", "概述", "重点", "主旨");
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private double clamp(double value) {
        if (value < 0.0d) {
            return 0.0d;
        }
        if (value > 1.0d) {
            return 1.0d;
        }
        return value;
    }
}
