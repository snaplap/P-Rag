package com.zzp.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.DataSourceType;
import com.zzp.rag.domain.MindMapCommand;
import com.zzp.rag.domain.RetrievalChunk;
import com.zzp.rag.domain.WebSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MockMcpToolClient implements McpToolClient {

    private static final Logger log = LoggerFactory.getLogger(MockMcpToolClient.class);

    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public MockMcpToolClient(RagProperties ragProperties, ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<WebSearchResult> searchWeb(String question, int topK) {
        if (!ragProperties.getMcp().isUseMock()) {
            List<WebSearchResult> remote = searchWebViaHttp(question, topK);
            if (!remote.isEmpty()) {
                return remote;
            }
        }

        int safeTopK = Math.max(1, topK);
        return List.of(
                new WebSearchResult(
                        "MockSearch-1",
                        "https://example.com/search/1",
                        "联网检索模拟结果：" + question + "（结果1）",
                        0.82d),
                new WebSearchResult(
                        "MockSearch-2",
                        "https://example.com/search/2",
                        "联网检索模拟结果：" + question + "（结果2）",
                        0.73d),
                new WebSearchResult(
                        "MockSearch-3",
                        "https://example.com/search/3",
                        "联网检索模拟结果：" + question + "（结果3）",
                        0.66d))
                .stream().limit(safeTopK).toList();
    }

    @Override
    public MindMapCommand generateMindMap(String question, String answer, DataSourceType sourceType,
            List<RetrievalChunk> evidence) {
        if (!ragProperties.getMcp().isUseMock()) {
            MindMapCommand remote = generateMindMapViaHttp(question, answer, sourceType, evidence);
            if (remote != null) {
                return remote;
            }
        }

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("topic", question);
        arguments.put("source", sourceType.name());
        arguments.put("summary", safeSnippet(answer));
        arguments.put("evidenceCount", evidence == null ? 0 : evidence.size());
        arguments.put("imageUrl", buildSvgMindMapDataUrl(question, answer, sourceType));

        return new MindMapCommand("mindmap.generate", arguments);
    }

    private String safeSnippet(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        int max = Math.min(180, answer.length());
        return answer.substring(0, max);
    }

    private List<WebSearchResult> searchWebViaHttp(String question, int topK) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", question);
            payload.put("topK", Math.max(1, topK));

            JsonNode root = postJson(ragProperties.getMcp().getWebSearchUrl(), payload);
            JsonNode resultsNode = root.path("results");
            if (!resultsNode.isArray() || resultsNode.isEmpty()) {
                return List.of();
            }

            List<WebSearchResult> results = new ArrayList<>();
            for (JsonNode node : resultsNode) {
                String title = node.path("title").asText("");
                String url = node.path("url").asText("");
                String snippet = node.path("snippet").asText("");
                double confidence = node.path("confidence").asDouble(0.65d);
                results.add(new WebSearchResult(title, url, snippet, confidence));
            }
            return results;
        } catch (Exception ex) {
            log.warn("MCP web search call failed, fallback to mock: {}", ex.getMessage());
            return List.of();
        }
    }

    private MindMapCommand generateMindMapViaHttp(
            String question,
            String answer,
            DataSourceType sourceType,
            List<RetrievalChunk> evidence) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("topic", question);
            payload.put("summary", safeSnippet(answer));
            payload.put("source", sourceType.name());
            payload.put("evidenceCount", evidence == null ? 0 : evidence.size());

            JsonNode root = postJson(ragProperties.getMcp().getDiagramUrl(), payload);
            String tool = root.path("tool").asText("mindmap.generate");

            Map<String, Object> arguments = new LinkedHashMap<>();
            JsonNode argsNode = root.path("arguments");
            if (argsNode.isObject()) {
                arguments = objectMapper.convertValue(argsNode, Map.class);
            } else {
                arguments.put("topic", question);
                arguments.put("source", sourceType.name());
                arguments.put("summary", safeSnippet(answer));
            }

            String imageUrl = root.path("imageUrl").asText("");
            if (!imageUrl.isBlank()) {
                arguments.put("imageUrl", imageUrl);
            }
            if (!arguments.containsKey("imageUrl")) {
                arguments.put("imageUrl", buildSvgMindMapDataUrl(question, answer, sourceType));
            }
            return new MindMapCommand(tool, arguments);
        } catch (Exception ex) {
            log.warn("MCP diagram call failed, fallback to mock: {}", ex.getMessage());
            return null;
        }
    }

    private JsonNode postJson(String url, Map<String, Object> payload) throws Exception {
        String body = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1000, ragProperties.getMcp().getCallTimeoutMs())))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("status=" + response.statusCode());
        }
        if (response.body() == null || response.body().isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(response.body());
    }

    private String buildSvgMindMapDataUrl(String question, String answer, DataSourceType sourceType) {
        String topic = xmlEscape(limit(question, 28));
        String summary = xmlEscape(limit(safeSnippet(answer), 56));
        String source = xmlEscape(sourceType == null ? "UNKNOWN" : sourceType.name());

        String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='960' height='540'>"
                + "<defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'>"
                + "<stop offset='0%' stop-color='#e8f6ff'/><stop offset='100%' stop-color='#cde9fb'/></linearGradient></defs>"
                + "<rect width='960' height='540' fill='url(#g)'/>"
                + "<rect x='350' y='46' width='260' height='68' rx='14' fill='#ffffff' stroke='#68b4df' stroke-width='2'/>"
                + "<text x='480' y='86' text-anchor='middle' fill='#1d5b7f' font-size='24' font-family='Arial'>"
                + topic + "</text>"
                + "<line x1='480' y1='114' x2='230' y2='230' stroke='#5ba8d4' stroke-width='3'/>"
                + "<line x1='480' y1='114' x2='730' y2='230' stroke='#5ba8d4' stroke-width='3'/>"
                + "<rect x='120' y='210' width='230' height='120' rx='12' fill='#ffffff' stroke='#8ec7e6'/>"
                + "<text x='235' y='248' text-anchor='middle' fill='#2a678c' font-size='18' font-family='Arial'>来源</text>"
                + "<text x='235' y='282' text-anchor='middle' fill='#2a678c' font-size='16' font-family='Arial'>"
                + source + "</text>"
                + "<rect x='610' y='210' width='230' height='120' rx='12' fill='#ffffff' stroke='#8ec7e6'/>"
                + "<text x='725' y='248' text-anchor='middle' fill='#2a678c' font-size='18' font-family='Arial'>摘要</text>"
                + "<text x='725' y='282' text-anchor='middle' fill='#2a678c' font-size='14' font-family='Arial'>"
                + summary + "</text>"
                + "</svg>";

        String encoded = Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        return "data:image/svg+xml;base64," + encoded;
    }

    private String limit(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen) + "...";
    }

    private String xmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
