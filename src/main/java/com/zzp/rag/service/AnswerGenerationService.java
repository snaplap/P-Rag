package com.zzp.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.ConversationTurn;
import com.zzp.rag.domain.DataSourceType;
import com.zzp.rag.domain.RetrievalChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AnswerGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AnswerGenerationService.class);

    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    public AnswerGenerationService(RagProperties ragProperties, ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
    }

    public String generateAnswer(
            String question,
            List<ConversationTurn> history,
            List<RetrievalChunk> evidence,
            DataSourceType sourceType) {
        String fallback = fallbackAnswer(question, history, evidence, sourceType);

        String llmAnswer = askLlmIfConfigured(question, history, evidence, sourceType);
        if (llmAnswer != null && !llmAnswer.isBlank() && !isLowValueAnswer(llmAnswer)) {
            return llmAnswer.trim();
        }
        return fallback;
    }

    private String fallbackAnswer(
            String question,
            List<ConversationTurn> history,
            List<RetrievalChunk> evidence,
            DataSourceType sourceType) {
        StringBuilder builder = new StringBuilder();
        builder.append("根据当前可用内容，回答如下：\n");

        if (evidence == null || evidence.isEmpty()) {
            if (sourceType == DataSourceType.KNOWLEDGE_BASE) {
                builder.append("我在当前知识库中没有找到足够信息来回答这个问题。\n");
                builder.append("你可以尝试换个问法，或补充更相关的文档后再提问。\n");
            } else {
                builder.append("当前可用资料不足，暂时无法给出可靠结论。\n");
            }
            return builder.toString();
        }

        builder.append("你问的是：").append(question).append("\n");

        RetrievalChunk primary = evidence.get(0);
        builder.append(trimSnippet(primary.content(), 260)).append("\n");

        if (evidence.size() > 1) {
            builder.append("\n补充信息：").append(trimSnippet(evidence.get(1).content(), 160)).append("\n");
        }

        if (history != null && !history.isEmpty()) {
            ConversationTurn latestTurn = history.get(history.size() - 1);
            builder.append("\n我结合了你上一轮的提问语境：")
                    .append(trimSnippet(latestTurn.question(), 48))
                    .append("。\n");
        }

        if (sourceType == DataSourceType.WEB) {
            builder.append("\n以上回答来自已检索到的公开资料。\n");
        } else if (sourceType == DataSourceType.HYBRID) {
            builder.append("\n以上回答综合了知识库与联网检索信息。\n");
        }
        return builder.toString();
    }

    private String askLlmIfConfigured(
            String question,
            List<ConversationTurn> history,
            List<RetrievalChunk> evidence,
            DataSourceType sourceType) {
        RagProperties.Llm llm = ragProperties.getLlm();
        if (isBlank(llm.getApiKey()) || isBlank(llm.getBaseUrl()) || isBlank(llm.getModel())) {
            return null;
        }

        try {
            String endpoint = llm.getBaseUrl().endsWith("/")
                    ? llm.getBaseUrl() + "chat/completions"
                    : llm.getBaseUrl() + "/chat/completions";

            String evidenceText = buildEvidenceText(evidence);
            String historyText = buildHistoryText(history);

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
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return null;
            }

            JsonNode contentNode = choices.get(0).path("message").path("content");
            if (contentNode.isMissingNode() || contentNode.isNull()) {
                return null;
            }
            String content = contentNode.asText();
            return content == null ? null : content.trim();
        } catch (Exception ex) {
            log.warn("Call LLM failed, fallback template answer: {}", ex.getMessage());
            return null;
        }
    }

    private List<Map<String, String>> buildMessages(
            String question,
            String evidenceText,
            String historyText,
            DataSourceType sourceType) {
        List<Map<String, String>> messages = new ArrayList<>();

        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append("你是企业级知识库问答助手。请只根据提供的证据作答。")
                .append("如果证据不足，明确说不确定，不要编造。")
                .append("不要输出‘没有提供任何实质性的文章信息或摘要’这类模板化拒答句。")
                .append("回答要面向最终用户，避免输出检索分数、缓存状态、内部工具细节。")
                .append("回答格式使用 Markdown，结构清晰，必要时使用列表。")
                .append("来源类型=")
                .append(labelSource(sourceType));

        messages.add(Map.of("role", "system", "content", systemPrompt.toString()));

        if (!historyText.isBlank()) {
            messages.add(Map.of("role", "user", "content", "历史对话摘要:\n" + historyText));
        }

        String userPrompt = "问题:\n" + question + "\n\n证据:\n" + evidenceText
                + "\n\n请输出简洁、直接、用户可读的回答。";
        messages.add(Map.of("role", "user", "content", userPrompt));
        return messages;
    }

    private String buildEvidenceText(List<RetrievalChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "无可用证据。";
        }

        StringBuilder builder = new StringBuilder();
        int limit = Math.min(4, evidence.size());
        for (int i = 0; i < limit; i++) {
            RetrievalChunk chunk = evidence.get(i);
            builder.append(i + 1)
                    .append(". ")
                    .append(trimSnippet(chunk.content(), 400))
                    .append("\n");
        }
        return builder.toString();
    }

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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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

    private boolean isLowValueAnswer(String answer) {
        String normalized = answer == null ? "" : answer.replaceAll("\\s+", "").toLowerCase();
        return normalized.contains("没有提供任何实质性的文章信息或摘要")
                || normalized.contains("未提供任何实质性的文章信息或摘要")
                || normalized.contains("没有提供文章信息")
                || normalized.contains("无法根据提供的内容回答")
                || normalized.contains("无法确定这篇文章的具体内容")
                || normalized.contains("无法确定文章具体内容");
    }

    private String labelSource(DataSourceType sourceType) {
        return switch (sourceType) {
            case KNOWLEDGE_BASE -> "知识库";
            case WEB -> "联网";
            case HYBRID -> "知识库+联网";
        };
    }
}
