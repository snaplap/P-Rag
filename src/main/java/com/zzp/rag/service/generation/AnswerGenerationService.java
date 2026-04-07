package com.zzp.rag.service.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.trace.ConversationTurn;
import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.model.RetrievalChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Service
public class AnswerGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AnswerGenerationService.class);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+.*$");
    private static final Pattern MARKDOWN_BULLET_PATTERN = Pattern.compile("^[-*+]\\s+.*$");

    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;
    private ChatClient chatClient;

    public AnswerGenerationService(RagProperties ragProperties, ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    public void setChatClientBuilder(ChatClient.Builder chatClientBuilder) {
        if (chatClientBuilder != null) {
            this.chatClient = chatClientBuilder.build();
        }
    }

    /**
     * 简化入口：仅返回答案文本。
     */
    public String generateAnswer(
            String question,
            List<ConversationTurn> history,
            List<RetrievalChunk> evidence,
            DataSourceType sourceType) {
        return generateAnswerWithDiagnostics(question, history, evidence, sourceType).answer();
    }

    /**
     * 带诊断的生成入口：优先调用 LLM，失败或低价值输出时回退到本地模板生成。
     */
    public GenerationOutcome generateAnswerWithDiagnostics(
            String question,
            List<ConversationTurn> history,
            List<RetrievalChunk> evidence,
            DataSourceType sourceType) {
        return generateAnswerWithDiagnostics(question, history, evidence, sourceType, null);
    }

    /**
     * 带诊断的生成入口，支持 token 级流式回调。
     */
    public GenerationOutcome generateAnswerWithDiagnostics(
            String question,
            List<ConversationTurn> history,
            List<RetrievalChunk> evidence,
            DataSourceType sourceType,
            Consumer<String> streamConsumer) {
        String fallback = fallbackAnswer(question, history, evidence, sourceType);

        LlmCallResult llmResult = askLlmIfConfigured(question, history, evidence, sourceType, streamConsumer);
        String llmAnswer = llmResult.answer();
        if (llmAnswer != null && !llmAnswer.isBlank() && !isLowValueAnswer(llmAnswer)) {
            return new GenerationOutcome(llmAnswer.trim(), true, null);
        }

        String fallbackReason = llmResult.reasonCode();
        if (llmAnswer != null && !llmAnswer.isBlank() && isLowValueAnswer(llmAnswer)) {
            fallbackReason = "LLM_LOW_VALUE_ANSWER";
        }
        if (fallbackReason == null || fallbackReason.isBlank()) {
            fallbackReason = "LLM_UNKNOWN_FALLBACK";
        }
        return new GenerationOutcome(fallback, false, fallbackReason);
    }

    /**
     * 本地回退回答生成。
     * 注意：该回答面向用户展示，因此必须保证自然可读且不暴露内部技术细节。
     */
    private String fallbackAnswer(
            String question,
            List<ConversationTurn> history,
            List<RetrievalChunk> evidence,
            DataSourceType sourceType) {
        if (evidence == null || evidence.isEmpty()) {
            return "这个问题目前信息不足，暂时无法给出准确结论。"
                    + "请补充时间、对象或关键背景后再试。";
        }

        StringBuilder builder = new StringBuilder();
        builder.append(composeEvidenceSummary(question, evidence));

        if (history != null && !history.isEmpty()) {
            ConversationTurn latestTurn = history.get(history.size() - 1);
            builder.append("\n\n");
            builder.append("结合你上一轮提问“")
                    .append(trimSnippet(latestTurn.question(), 48))
                    .append("”，我优先按当前会话语境来组织以上结论。");
        }

        builder.append("\n\n可参考来源：\n");
        int refLimit = Math.min(3, evidence.size());
        for (int i = 0; i < refLimit; i++) {
            RetrievalChunk chunk = evidence.get(i);
            builder.append(i + 1)
                    .append(". ")
                    .append(readableSource(chunk, i + 1))
                    .append("\n");
        }

        return builder.toString().trim();
    }

    /**
     * 将证据聚合成一句或多句可读摘要。
     */
    private String composeEvidenceSummary(String question, List<RetrievalChunk> evidence) {
        List<String> keyPoints = extractKeyPoints(evidence, 3);
        if (keyPoints.isEmpty()) {
            return "目前可用信息不足，暂时无法覆盖你问题中的全部细节。";
        }

        if (isProceduralQuestion(question) && keyPoints.size() >= 2) {
            StringBuilder builder = new StringBuilder();
            builder.append("可以先按这个顺序处理：\n");
            for (int i = 0; i < keyPoints.size(); i++) {
                builder.append(i + 1)
                        .append(". ")
                        .append(keyPoints.get(i))
                        .append("\n");
            }
            return builder.toString().trim();
        }

        if (keyPoints.size() == 1) {
            return "目前可以确认，" + ensureSentenceEnding(keyPoints.get(0));
        }

        return "核心信息是："
                + String.join("；", keyPoints)
                + "。";
    }

    /**
     * 判断是否为“步骤/操作型”问题。
     */
    private boolean isProceduralQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        return containsAny(lower, "怎么", "如何", "步骤", "流程", "怎么做", "如何做", "操作", "指南", "排查", "修复");
    }

    /**
     * 补全句号，避免回答断句生硬。
     */
    private String ensureSentenceEnding(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.trim();
        if (normalized.endsWith("。") || normalized.endsWith("！") || normalized.endsWith("？")) {
            return normalized;
        }
        return normalized + "。";
    }

    /**
     * 字符串是否包含任一关键词。
     */
    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 来源展示脱敏：优先可读标签，避免输出内部 ID。
     */
    private String readableSource(RetrievalChunk chunk, int index) {
        if (chunk == null) {
            return "参考资料" + index;
        }

        String raw = firstNonBlank(chunk.documentId(), chunk.id());
        if (raw == null || raw.isBlank()) {
            return "参考资料" + index;
        }

        String normalized = raw.replace("\n", " ").trim();
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            return normalizeUrlSource(normalized);
        }
        if (looksLikeInternalId(normalized)) {
            return "参考资料" + index;
        }
        return trimSnippet(normalized, 48);
    }

    /**
     * 取第一个非空字符串。
     */
    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    /**
     * URL 来源归一化为域名，提升可读性。
     */
    private String normalizeUrlSource(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return trimSnippet(url, 48);
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception ex) {
            return trimSnippet(url, 48);
        }
    }

    /**
     * 判断来源是否像内部 ID（如 UUID、chunk-xx）。
     */
    private boolean looksLikeInternalId(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (UUID_PATTERN.matcher(value).matches()) {
            return true;
        }
        return lower.matches("web-\\d+")
                || lower.matches("chunk-\\d+")
                || lower.matches("doc-\\d+")
                || value.length() > 64;
    }

    /**
     * 清洗候选句中的 markdown 标记。
     */
    private String sanitizeCandidateLine(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\r', ' ').replace('\n', ' ').trim();
        normalized = normalized.replaceAll("^#{1,6}\\s*", "");
        normalized = normalized.replaceAll("^[-*+]\\s*", "");
        normalized = normalized.replaceAll("^\\d+[\\.)]\\s*", "");
        normalized = normalized.replace("`", "");
        normalized = normalized.replace("**", "");
        return normalized.trim();
    }

    /**
     * 过滤不适合用于摘要的候选行。
     */
    private boolean shouldSkipCandidateLine(String line) {
        if (line == null || line.isBlank()) {
            return true;
        }
        String normalized = line.trim();
        if (normalized.length() < 8) {
            return true;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return MARKDOWN_HEADING_PATTERN.matcher(normalized).matches()
                || MARKDOWN_BULLET_PATTERN.matcher(normalized).matches()
                || lower.startsWith("来源")
                || lower.contains("score=")
                || lower.contains("http://")
                || lower.contains("https://");
    }

    /**
     * 在配置完整时调用 LLM，返回文本与失败原因。
     */
    private LlmCallResult askLlmIfConfigured(
            String question,
            List<ConversationTurn> history,
            List<RetrievalChunk> evidence,
            DataSourceType sourceType,
            Consumer<String> streamConsumer) {
        RagProperties.Llm llm = ragProperties.getLlm();
        if (chatClient == null && (isBlank(llm.getApiKey()) || isBlank(llm.getBaseUrl()) || isBlank(llm.getModel()))) {
            return new LlmCallResult(null, "LLM_NOT_CONFIGURED");
        }

        try {
            String evidenceText = buildEvidenceText(evidence);
            String historyText = buildHistoryText(history);
            String systemPrompt = buildSystemPrompt(sourceType);
            String userPrompt = buildUserPrompt(question, evidenceText, historyText);

            if (chatClient != null) {
                return askLlmViaSpringAi(systemPrompt, userPrompt, streamConsumer);
            }

            if (isBlank(llm.getApiKey()) || isBlank(llm.getBaseUrl()) || isBlank(llm.getModel())) {
                return new LlmCallResult(null, "LLM_NOT_CONFIGURED");
            }

            return askLlmViaHttp(llm, question, evidenceText, historyText, sourceType);
        } catch (Exception ex) {
            log.warn("Call LLM failed, fallback template answer: {}", ex.getMessage());
            return new LlmCallResult(null, "LLM_EXCEPTION");
        }
    }

    private LlmCallResult askLlmViaSpringAi(String systemPrompt, String userPrompt, Consumer<String> streamConsumer) {
        try {
            List<String> tokens = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        if (streamConsumer != null && token != null && !token.isBlank()) {
                            streamConsumer.accept(token);
                        }
                    })
                    .collectList()
                    .block();

            if (tokens == null || tokens.isEmpty()) {
                return new LlmCallResult(null, "LLM_EMPTY_CONTENT");
            }

            String content = String.join("", tokens).trim();
            if (content.isBlank()) {
                return new LlmCallResult(null, "LLM_EMPTY_CONTENT");
            }
            return new LlmCallResult(content, null);
        } catch (Exception ex) {
            log.warn("Spring AI streaming call failed, fallback to HTTP: {}", ex.getMessage());
            return new LlmCallResult(null, "LLM_SPRING_AI_EXCEPTION");
        }
    }

    private LlmCallResult askLlmViaHttp(
            RagProperties.Llm llm,
            String question,
            String evidenceText,
            String historyText,
            DataSourceType sourceType) throws Exception {
        String endpoint = llm.getBaseUrl().endsWith("/")
                ? llm.getBaseUrl() + "chat/completions"
                : llm.getBaseUrl() + "/chat/completions";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", llm.getModel());
        payload.put("temperature", 0.2);
        payload.put("stream", false);
        payload.put("messages", buildMessages(question, evidenceText, historyText, sourceType));

        String body = objectMapper.writeValueAsString(payload);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, llm.getConnectTimeoutMs())))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMillis(Math.max(3000, llm.getReadTimeoutMs())))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + llm.getApiKey().trim())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("LLM response status not success: {}", response.statusCode());
            return new LlmCallResult(null, "LLM_HTTP_" + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return new LlmCallResult(null, "LLM_EMPTY_CHOICES");
        }

        JsonNode contentNode = choices.get(0).path("message").path("content");
        if (contentNode.isMissingNode() || contentNode.isNull()) {
            return new LlmCallResult(null, "LLM_EMPTY_CONTENT");
        }
        String content = contentNode.asText();
        return new LlmCallResult(content == null ? null : content.trim(), null);
    }

    private String buildSystemPrompt(DataSourceType sourceType) {
        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append("你是企业级知识库问答助手。请只根据提供的证据作答。")
                .append("如果证据不足，明确说不确定，不要编造。")
                .append("不要输出‘没有提供任何实质性的文章信息或摘要’这类模板化拒答句。")
                .append("回答要面向最终用户，避免输出检索分数、缓存状态、内部工具细节。")
                .append("回答默认使用2-4句自然段，仅当用户明确要求步骤时再使用列表。")
                .append("来源引用使用可读名称，不要输出内部ID。")
                .append("来源类型=")
                .append(labelSource(sourceType));
        return systemPrompt.toString();
    }

    private String buildUserPrompt(String question, String evidenceText, String historyText) {
        StringBuilder userPrompt = new StringBuilder();
        if (!historyText.isBlank()) {
            userPrompt.append("历史对话摘要:\n").append(historyText).append("\n\n");
        }
        userPrompt.append("问题:\n").append(question)
                .append("\n\n证据:\n").append(evidenceText)
                .append("\n\n请输出简洁、直接、用户可读的回答。若证据不足，请明确缺少哪些关键证据。");
        return userPrompt.toString();
    }

    /**
     * 构造 LLM 消息列表。
     */
    private List<Map<String, String>> buildMessages(
            String question,
            String evidenceText,
            String historyText,
            DataSourceType sourceType) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt(sourceType)));
        messages.add(Map.of("role", "user", "content", buildUserPrompt(question, evidenceText, historyText)));
        return messages;
    }

    /**
     * 构建证据文本（含来源与分数）供 LLM 参考。
     */
    private String buildEvidenceText(List<RetrievalChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "无可用证据。";
        }

        StringBuilder builder = new StringBuilder();
        int limit = Math.min(6, evidence.size());
        for (int i = 0; i < limit; i++) {
            RetrievalChunk chunk = evidence.get(i);
            builder.append("[")
                    .append(i + 1)
                    .append("] source=")
                    .append(trimSnippet(safeSource(chunk), 120))
                    .append(", score=")
                    .append(formatScore(chunk.score()))
                    .append("\n")
                    .append(trimSnippet(chunk.content(), 420))
                    .append("\n");
        }
        return builder.toString();
    }

    /**
     * 构建历史对话摘要，默认保留最近两轮。
     */
    private String buildHistoryText(List<ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        int from = Math.max(0, history.size() - 2);
        for (int i = from; i < history.size(); i++) {
            ConversationTurn turn = history.get(i);
            builder.append("Q:").append(trimSnippet(turn.question(), 120)).append("\n");
            builder.append("A:").append(trimSnippet(turn.answer(), 180)).append("\n");
        }
        return builder.toString();
    }

    /**
     * 判空工具。
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 文本裁剪，超长加省略号。
     */
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

    /**
     * 判断 LLM 输出是否属于低价值模板化回答。
     */
    private boolean isLowValueAnswer(String answer) {
        String normalized = answer == null ? "" : answer.replaceAll("\\s+", "").toLowerCase();
        return normalized.contains("没有提供任何实质性的文章信息或摘要")
                || normalized.contains("未提供任何实质性的文章信息或摘要")
                || normalized.contains("没有提供文章信息")
                || normalized.contains("无法根据提供的内容回答")
                || normalized.contains("无法确定这篇文章的具体内容")
                || normalized.contains("无法确定文章具体内容")
                || normalized.contains("根据当前可用内容，回答如下")
                || normalized.contains("##结论")
                || normalized.contains("##依据要点");
    }

    /**
     * 从证据中抽取核心句，去重并过滤噪声。
     */
    private List<String> extractKeyPoints(List<RetrievalChunk> evidence, int maxPoints) {
        List<String> points = new ArrayList<>();
        if (evidence == null || evidence.isEmpty()) {
            return points;
        }

        Set<String> dedup = new LinkedHashSet<>();
        int scanLimit = Math.min(evidence.size(), Math.max(2, maxPoints * 3));
        for (int i = 0; i < scanLimit; i++) {
            RetrievalChunk chunk = evidence.get(i);
            if (chunk == null || chunk.content() == null || chunk.content().isBlank()) {
                continue;
            }

            String[] sentences = chunk.content().split("[。！？!?\\n]");
            for (String sentence : sentences) {
                String candidate = sanitizeCandidateLine(sentence);
                if (shouldSkipCandidateLine(candidate)) {
                    continue;
                }
                String normalizedKey = candidate.replaceAll("\\s+", "")
                        .toLowerCase(Locale.ROOT);
                if (dedup.add(normalizedKey)) {
                    points.add(trimSnippet(candidate, 96));
                    if (points.size() >= maxPoints) {
                        return points;
                    }
                }
            }
        }
        return points;
    }

    /**
     * 安全来源展示。
     */
    private String safeSource(RetrievalChunk chunk) {
        return readableSource(chunk, 1);
    }

    /**
     * 分数格式化输出。
     */
    private String formatScore(double score) {
        return String.format("%.3f", Math.max(0.0d, Math.min(1.0d, score)));
    }

    /**
     * 来源类型展示文本。
     */
    private String labelSource(DataSourceType sourceType) {
        return switch (sourceType) {
            case KNOWLEDGE_BASE -> "知识库";
            case WEB -> "联网";
            case HYBRID -> "知识库+联网";
        };
    }

    public record GenerationOutcome(String answer, boolean llmUsed, String fallbackReason) {
    }

    private record LlmCallResult(String answer, String reasonCode) {
    }
}
