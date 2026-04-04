package com.zzp.rag.service;

import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.ConversationTurn;
import com.zzp.rag.domain.DataSourceType;
import com.zzp.rag.domain.RetrievalChunk;
import org.springframework.stereotype.Service;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RuntimeMetricsService {

    private static final Pattern EN_WORD_PATTERN = Pattern.compile("[A-Za-z0-9_]+(?:[-'][A-Za-z0-9_]+)?");
    private static final Pattern CJK_PATTERN = Pattern.compile("[\\p{IsHan}]");

    private final RagProperties ragProperties;
    private final AtomicLong totalRequests = new AtomicLong(0L);
    private final AtomicLong totalErrors = new AtomicLong(0L);
    private final ConcurrentLinkedDeque<Long> recentRequestTimestamps = new ConcurrentLinkedDeque<>();

    public RuntimeMetricsService(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public RequestTracker startRequest() {
        long nowMs = System.currentTimeMillis();
        totalRequests.incrementAndGet();
        recentRequestTimestamps.addLast(nowMs);
        trimOldRequests(nowMs);
        return new RequestTracker(nowMs, System.nanoTime());
    }

    public void markError() {
        totalErrors.incrementAndGet();
    }

    public Map<String, Object> buildLogMetrics(
            RequestTracker tracker,
            String question,
            List<ConversationTurn> history,
            List<RetrievalChunk> evidence,
            String answer,
            DataSourceType sourceType,
            long retrievalMs,
            long generationMs,
            boolean cacheHit) {
        long nowMs = System.currentTimeMillis();
        trimOldRequests(nowMs);

        long latencyMs = Math.max(1L, (System.nanoTime() - tracker.startNanoTime()) / 1_000_000L);
        double qps = recentRequestTimestamps.size() / 60.0d;
        double errorRate = totalRequests.get() == 0L
                ? 0.0d
                : (totalErrors.get() * 100.0d / totalRequests.get());

        int contextChars = totalContextChars(evidence);
        int retrievalTokens = totalEvidenceTokens(evidence);
        double duplicateRatio = contextDuplicateRatio(evidence);
        double kbHitRatio = knowledgeBaseHitRatio(evidence);

        int inputTokens = estimateInputTokens(question, history, evidence);
        int outputTokens = estimateTokens(answer);
        double cost = computeCost(inputTokens, outputTokens);

        boolean emptyAnswer = answer == null || answer.trim().isEmpty();
        boolean hasReference = hasReference(answer, evidence);
        boolean containsFallback = containsFallback(answer);

        Map<String, Object> systemHealth = new LinkedHashMap<>();
        systemHealth.put("响应时间(ms)", latencyMs);
        systemHealth.put("检索耗时(ms)", retrievalMs);
        systemHealth.put("生成耗时(ms)", generationMs);
        systemHealth.put("QPS(1分钟均值)", formatDecimal(qps));
        systemHealth.put("错误率", formatPercent(errorRate));
        systemHealth.put("缓存命中", yesNo(cacheHit));

        Map<String, Object> retrievalLayer = new LinkedHashMap<>();
        retrievalLayer.put("检索片段数", evidence == null ? 0 : evidence.size());
        retrievalLayer.put("Context长度(字符)", contextChars);
        retrievalLayer.put("每次检索Token数(估算)", retrievalTokens);
        retrievalLayer.put("上下文长度判断", contextLengthJudgement(retrievalTokens));
        retrievalLayer.put("上下文重复率", formatPercent(duplicateRatio * 100.0d));
        retrievalLayer.put("Context是否重复", yesNo(duplicateRatio >= 0.30d));
        retrievalLayer.put("知识库片段命中占比", formatPercent(kbHitRatio * 100.0d));
        retrievalLayer.put("标签命中率", "暂无标签，无法计算");
        retrievalLayer.put("是否命中正确文档", "暂无标准答案，无法判定");
        retrievalLayer.put("Recall@K", "暂无标准答案，无法计算");

        Map<String, Object> costLayer = new LinkedHashMap<>();
        costLayer.put("输入Tokens(估算)", inputTokens);
        costLayer.put("输出Tokens(估算)", outputTokens);
        costLayer.put("单次请求成本(估算)", formatCurrency(cost));

        Map<String, Object> structuralRules = new LinkedHashMap<>();
        structuralRules.put("是否返回空回答", yesNo(emptyAnswer));
        structuralRules.put("是否包含引用", yesNo(hasReference));
        structuralRules.put("是否包含“我不知道”类fallback", yesNo(containsFallback));
        structuralRules.put("来源类型", labelSource(sourceType));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("系统健康度", systemHealth);
        result.put("检索层指标", retrievalLayer);
        result.put("成本指标", costLayer);
        result.put("结构性规则", structuralRules);
        return result;
    }

    private void trimOldRequests(long nowMs) {
        long cutoff = nowMs - 60_000L;
        Long head = recentRequestTimestamps.peekFirst();
        while (head != null && head < cutoff) {
            recentRequestTimestamps.pollFirst();
            head = recentRequestTimestamps.peekFirst();
        }
    }

    private int totalContextChars(List<RetrievalChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (RetrievalChunk chunk : evidence) {
            if (chunk != null && chunk.content() != null) {
                sum += chunk.content().length();
            }
        }
        return sum;
    }

    private int totalEvidenceTokens(List<RetrievalChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (RetrievalChunk chunk : evidence) {
            if (chunk != null && chunk.content() != null) {
                sum += estimateTokens(chunk.content());
            }
        }
        return sum;
    }

    private double contextDuplicateRatio(List<RetrievalChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return 0.0d;
        }
        List<String> normalized = new ArrayList<>();
        for (RetrievalChunk chunk : evidence) {
            if (chunk == null || chunk.content() == null) {
                continue;
            }
            String content = chunk.content().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            if (!content.isEmpty()) {
                normalized.add(content);
            }
        }
        if (normalized.isEmpty()) {
            return 0.0d;
        }
        long unique = normalized.stream().distinct().count();
        return Math.max(0.0d, 1.0d - (unique * 1.0d / normalized.size()));
    }

    private double knowledgeBaseHitRatio(List<RetrievalChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return 0.0d;
        }
        long total = 0L;
        long kbHits = 0L;
        for (RetrievalChunk chunk : evidence) {
            if (chunk == null) {
                continue;
            }
            total++;
            if (chunk.sourceType() == DataSourceType.KNOWLEDGE_BASE) {
                kbHits++;
            }
        }
        if (total == 0L) {
            return 0.0d;
        }
        return kbHits * 1.0d / total;
    }

    private int estimateInputTokens(String question, List<ConversationTurn> history, List<RetrievalChunk> evidence) {
        int sum = estimateTokens(question);
        if (history != null) {
            for (ConversationTurn turn : history) {
                if (turn == null) {
                    continue;
                }
                sum += estimateTokens(turn.question());
                sum += estimateTokens(turn.answer());
            }
        }
        if (evidence != null) {
            for (RetrievalChunk chunk : evidence) {
                if (chunk != null) {
                    sum += estimateTokens(chunk.content());
                }
            }
        }
        return sum;
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        String normalized = text.trim();
        int cjkCount = 0;
        Matcher cjkMatcher = CJK_PATTERN.matcher(normalized);
        while (cjkMatcher.find()) {
            cjkCount++;
        }

        int enWords = 0;
        Matcher enMatcher = EN_WORD_PATTERN.matcher(normalized);
        while (enMatcher.find()) {
            enWords++;
        }

        int punctuationOrOther = Math.max(0, normalized.length() - cjkCount - enWords);
        int approx = cjkCount + enWords + (punctuationOrOther / 4);
        return Math.max(1, approx);
    }

    private boolean hasReference(String answer, List<RetrievalChunk> evidence) {
        if (evidence != null && !evidence.isEmpty()) {
            return true;
        }
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String lower = answer.toLowerCase(Locale.ROOT);
        return lower.contains("http://")
                || lower.contains("https://")
                || lower.contains("[参考")
                || lower.contains("来源")
                || lower.contains("引用");
    }

    private boolean containsFallback(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String lower = answer.toLowerCase(Locale.ROOT);
        return lower.contains("我不知道")
                || lower.contains("不确定")
                || lower.contains("无法确定")
                || lower.contains("证据不足")
                || lower.contains("不清楚");
    }

    private String contextLengthJudgement(int tokens) {
        if (tokens <= 0) {
            return "无上下文";
        }
        if (tokens < 120) {
            return "偏短";
        }
        if (tokens > 4000) {
            return "偏长";
        }
        return "正常";
    }

    private double computeCost(int inputTokens, int outputTokens) {
        double inputCost = (inputTokens / 1000.0d) * ragProperties.getMetrics().getInputCostPer1kTokens();
        double outputCost = (outputTokens / 1000.0d) * ragProperties.getMetrics().getOutputCostPer1kTokens();
        return inputCost + outputCost;
    }

    private String labelSource(DataSourceType sourceType) {
        if (sourceType == null) {
            return "未知";
        }
        return switch (sourceType) {
            case KNOWLEDGE_BASE -> "知识库";
            case WEB -> "联网";
            case HYBRID -> "知识库+联网";
        };
    }

    private String formatCurrency(double value) {
        return String.format(Locale.ROOT, "￥%.6f", Math.max(0.0d, value));
    }

    private String formatPercent(double percent) {
        DecimalFormat format = new DecimalFormat("0.00");
        return format.format(Math.max(0.0d, percent)) + "%";
    }

    private String formatDecimal(double value) {
        DecimalFormat format = new DecimalFormat("0.00");
        return format.format(Math.max(0.0d, value));
    }

    private String yesNo(boolean value) {
        return value ? "是" : "否";
    }

    public record RequestTracker(long requestStartMs, long startNanoTime) {
    }
}
