package com.zzp.rag.service.audit;

import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.trace.ConversationTurn;
import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.model.RetrievalChunk;
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
/**
 * 运行时指标服务，负责汇总延迟、成本、检索质量与回答质量。
 */
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

    /**
     * 记录请求开始时间并返回追踪对象。
     */
    public RequestTracker startRequest() {
        long nowMs = System.currentTimeMillis();
        totalRequests.incrementAndGet();
        recentRequestTimestamps.addLast(nowMs);
        trimOldRequests(nowMs);
        return new RequestTracker(nowMs, System.nanoTime());
    }

    /**
     * 标记一次请求错误。
     */
    public void markError() {
        totalErrors.incrementAndGet();
    }

    /**
     * 构建前端展示和排障使用的指标结构。
     */
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
        double avgScore = averageEvidenceScore(evidence);
        double highQualityRatio = highQualityEvidenceRatio(evidence, 0.72d);
        int answerLength = answer == null ? 0 : answer.trim().length();
        double qualityScore = computeQualityScore(
                avgScore,
                highQualityRatio,
                duplicateRatio,
                emptyAnswer,
                containsFallback,
                hasReference,
                answerLength);

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

        Map<String, Object> qualityLayer = new LinkedHashMap<>();
        qualityLayer.put("回答质量评分(0-100)", formatDecimal(qualityScore));
        qualityLayer.put("平均证据分", formatDecimal(avgScore * 100.0d) + "%");
        qualityLayer.put("高质量证据占比", formatPercent(highQualityRatio * 100.0d));
        qualityLayer.put("回答长度(字符)", answerLength);
        qualityLayer.put("优化建议", buildOptimizationAdvice(
                retrievalTokens,
                duplicateRatio,
                avgScore,
                highQualityRatio,
                containsFallback,
                hasReference,
                emptyAnswer,
                answerLength));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("系统健康度", systemHealth);
        result.put("检索层指标", retrievalLayer);
        result.put("成本指标", costLayer);
        result.put("结构性规则", structuralRules);
        result.put("回答质量", qualityLayer);
        return result;
    }

    /**
     * 清理 1 分钟窗口之外的请求时间戳。
     */
    private void trimOldRequests(long nowMs) {
        long cutoff = nowMs - 60_000L;
        Long head = recentRequestTimestamps.peekFirst();
        while (head != null && head < cutoff) {
            recentRequestTimestamps.pollFirst();
            head = recentRequestTimestamps.peekFirst();
        }
    }

    /**
     * 统计证据总字符数。
     */
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

    /**
     * 估算证据总 token 数。
     */
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

    /**
     * 计算上下文重复率。
     */
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

    /**
     * 计算知识库证据命中占比。
     */
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

    /**
     * 计算证据平均分。
     */
    private double averageEvidenceScore(List<RetrievalChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return 0.0d;
        }

        double sum = 0.0d;
        long count = 0L;
        for (RetrievalChunk chunk : evidence) {
            if (chunk == null) {
                continue;
            }
            sum += Math.max(0.0d, Math.min(1.0d, chunk.score()));
            count++;
        }
        if (count == 0L) {
            return 0.0d;
        }
        return sum / count;
    }

    /**
     * 计算高质量证据占比。
     */
    private double highQualityEvidenceRatio(List<RetrievalChunk> evidence, double threshold) {
        if (evidence == null || evidence.isEmpty()) {
            return 0.0d;
        }

        long total = evidence.stream().filter(v -> v != null).count();
        if (total == 0L) {
            return 0.0d;
        }

        long highQuality = evidence.stream()
                .filter(v -> v != null && v.score() >= threshold)
                .count();
        return highQuality * 1.0d / total;
    }

    /**
     * 综合计算回答质量评分。
     */
    private double computeQualityScore(
            double avgScore,
            double highQualityRatio,
            double duplicateRatio,
            boolean emptyAnswer,
            boolean containsFallback,
            boolean hasReference,
            int answerLength) {
        double score = 0.0d;
        score += avgScore * 45.0d;
        score += highQualityRatio * 20.0d;
        score += (1.0d - Math.min(1.0d, duplicateRatio)) * 10.0d;
        score += hasReference ? 10.0d : 0.0d;
        score += answerLength >= 120 ? 10.0d : 5.0d;
        if (emptyAnswer) {
            score -= 30.0d;
        }
        if (containsFallback) {
            score -= 15.0d;
        }
        return Math.max(0.0d, Math.min(100.0d, score));
    }

    /**
     * 生成优化建议文本。
     */
    private String buildOptimizationAdvice(
            int retrievalTokens,
            double duplicateRatio,
            double avgScore,
            double highQualityRatio,
            boolean containsFallback,
            boolean hasReference,
            boolean emptyAnswer,
            int answerLength) {
        List<String> advices = new ArrayList<>();

        if (retrievalTokens < 180) {
            advices.add("上下文偏短：可提高 topK 或补充更完整文档");
        }
        if (duplicateRatio >= 0.30d) {
            advices.add("上下文重复偏高：可优化切片粒度和重叠参数");
        }
        if (avgScore < 0.55d || highQualityRatio < 0.4d) {
            advices.add("证据相关性偏低：建议优化检索阈值与重排模型");
        }
        if (!hasReference) {
            advices.add("回答缺少来源：建议在生成提示词中强制输出引用");
        }
        if (containsFallback) {
            advices.add("命中回退模板：优先排查 LLM 配置和联网检索链路");
        }
        if (emptyAnswer || answerLength < 80) {
            advices.add("回答过短：建议增加证据条数并放宽生成长度");
        }
        if (advices.isEmpty()) {
            advices.add("当前回答质量稳定，可继续通过标注数据做针对性评估");
        }
        return String.join("；", advices);
    }

    /**
     * 估算输入 token。
     */
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

    /**
     * 估算文本 token。
     */
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

    /**
     * 判断回答是否包含来源信息。
     */
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

    /**
     * 判断回答是否命中 fallback 语气。
     */
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

    /**
     * 判断上下文长度区间。
     */
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

    /**
     * 估算请求成本。
     */
    private double computeCost(int inputTokens, int outputTokens) {
        double inputCost = (inputTokens / 1000.0d) * ragProperties.getMetrics().getInputCostPer1kTokens();
        double outputCost = (outputTokens / 1000.0d) * ragProperties.getMetrics().getOutputCostPer1kTokens();
        return inputCost + outputCost;
    }

    /**
     * 来源类型标签。
     */
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

    /**
     * 货币格式化。
     */
    private String formatCurrency(double value) {
        return String.format(Locale.ROOT, "￥%.6f", Math.max(0.0d, value));
    }

    /**
     * 百分比格式化。
     */
    private String formatPercent(double percent) {
        DecimalFormat format = new DecimalFormat("0.00");
        return format.format(Math.max(0.0d, percent)) + "%";
    }

    /**
     * 小数格式化。
     */
    private String formatDecimal(double value) {
        DecimalFormat format = new DecimalFormat("0.00");
        return format.format(Math.max(0.0d, value));
    }

    /**
     * 布尔值转是/否。
     */
    private String yesNo(boolean value) {
        return value ? "是" : "否";
    }

    public record RequestTracker(long requestStartMs, long startNanoTime) {
    }
}
