package com.zzp.rag.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.dto.MindMapCommand;
import com.zzp.rag.domain.model.RetrievalChunk;
import com.zzp.rag.domain.dto.WebSearchResult;
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
import java.util.Locale;

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
        arguments.put("imageUrl", buildSvgMindMapDataUrl(question, answer, sourceType, evidence));

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
            JsonNode resultsNode = root.isArray() ? root : root.path("results");
            if (!resultsNode.isArray() || resultsNode.isEmpty()) {
                return List.of();
            }

            List<WebSearchResult> results = new ArrayList<>();
            for (JsonNode node : resultsNode) {
                String title = node.path("title").asText("");
                String url = node.path("url").asText("");
                String snippet = node.path("snippet").asText("");
                if (snippet.isBlank()) {
                    snippet = node.path("content").asText("");
                }
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
            if (imageUrl.isBlank()) {
                imageUrl = root.path("diagramUrl").asText("");
            }
            if (!imageUrl.isBlank()) {
                arguments.put("imageUrl", imageUrl);
            }
            if (!arguments.containsKey("imageUrl")) {
                arguments.put("imageUrl", buildSvgMindMapDataUrl(question, answer, sourceType, evidence));
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

    private String buildSvgMindMapDataUrl(
            String question,
            String answer,
            DataSourceType sourceType,
            List<RetrievalChunk> evidence) {
        String topic = xmlEscape(limit(question, 20));
        String source = xmlEscape(sourceType == null ? "UNKNOWN" : sourceType.name());
        List<MindBranch> branches = buildMindMapBranches(answer, evidence);

        int[][] branchBox = {
                { 90, 188 },
                { 670, 188 },
                { 380, 332 }
        };
        int[][][] childBox = {
                { { 24, 300 }, { 230, 300 } },
                { { 604, 300 }, { 810, 300 } },
                { { 314, 444 }, { 520, 444 } }
        };

        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns='http://www.w3.org/2000/svg' width='980' height='580'>")
                .append("<defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'>")
                .append("<stop offset='0%' stop-color='#eef9ff'/><stop offset='100%' stop-color='#d6efff'/></linearGradient></defs>")
                .append("<rect width='980' height='580' fill='url(#g)'/>");

        appendNode(svg, 350, 42, 280, 66, topic, 22, "#ffffff", "#5fa9d6", "#194f72");
        appendText(svg, 36, 548, "来源: " + source, 14, "#356789", "start");

        for (int i = 0; i < 3; i++) {
            MindBranch branch = branches.get(i);
            int bx = branchBox[i][0];
            int by = branchBox[i][1];

            appendLine(svg, 490, 108, bx + 100, by, "#4f9dcc", 2.8d);
            appendNode(svg, bx, by, 200, 56, xmlEscape(limit(branch.title(), 12)), 17, "#ffffff", "#7cbce3", "#1f5f86");

            int leftChildX = childBox[i][0][0];
            int leftChildY = childBox[i][0][1];
            int rightChildX = childBox[i][1][0];
            int rightChildY = childBox[i][1][1];

            appendLine(svg, bx + 100, by + 56, leftChildX + 74, leftChildY, "#7db5d9", 2.2d);
            appendLine(svg, bx + 100, by + 56, rightChildX + 74, rightChildY, "#7db5d9", 2.2d);
            appendNode(svg, leftChildX, leftChildY, 148, 48, xmlEscape(limit(branch.leftLeaf(), 16)), 13, "#f9fdff",
                    "#b8daf0", "#2d5f80");
            appendNode(svg, rightChildX, rightChildY, 148, 48, xmlEscape(limit(branch.rightLeaf(), 16)), 13, "#f9fdff",
                    "#b8daf0", "#2d5f80");
        }

        svg.append("</svg>");
        String encoded = Base64.getEncoder().encodeToString(svg.toString().getBytes(StandardCharsets.UTF_8));
        return "data:image/svg+xml;base64," + encoded;
    }

    private List<MindBranch> buildMindMapBranches(String answer, List<RetrievalChunk> evidence) {
        List<String> candidates = new ArrayList<>();

        if (answer != null && !answer.isBlank()) {
            String[] lines = answer.split("\\r?\\n");
            for (String line : lines) {
                String normalized = normalizeNodeText(line);
                if (!normalized.isBlank()) {
                    candidates.add(normalized);
                }
            }
        }

        if (evidence != null) {
            for (int i = 0; i < Math.min(2, evidence.size()); i++) {
                RetrievalChunk chunk = evidence.get(i);
                if (chunk == null || chunk.content() == null) {
                    continue;
                }
                String[] parts = chunk.content().split("[。；;\\n]");
                for (String part : parts) {
                    String normalized = normalizeNodeText(part);
                    if (!normalized.isBlank()) {
                        candidates.add(normalized);
                    }
                    if (candidates.size() >= 20) {
                        break;
                    }
                }
                if (candidates.size() >= 20) {
                    break;
                }
            }
        }

        List<String> unique = candidates.stream()
                .map(v -> v.toLowerCase(Locale.ROOT))
                .distinct()
                .map(v -> v.replace("\u0000", ""))
                .toList();

        List<String> readable = new ArrayList<>();
        for (String item : unique) {
            if (item == null || item.isBlank()) {
                continue;
            }
            readable.add(item);
            if (readable.size() >= 12) {
                break;
            }
        }

        if (readable.isEmpty()) {
            readable = List.of("主题背景", "关键要点", "结论建议", "核心信息", "应用场景", "风险提示");
        }

        List<MindBranch> branches = new ArrayList<>();
        branches.add(new MindBranch(
                pick(readable, 0, "主题背景"),
                pick(readable, 3, "背景说明"),
                pick(readable, 4, "上下文")));
        branches.add(new MindBranch(
                pick(readable, 1, "关键要点"),
                pick(readable, 5, "核心内容"),
                pick(readable, 6, "细节补充")));
        branches.add(new MindBranch(
                pick(readable, 2, "结论建议"),
                pick(readable, 7, "风险提示"),
                pick(readable, 8, "后续行动")));
        return branches;
    }

    private String pick(List<String> candidates, int index, String fallback) {
        if (candidates == null || index < 0 || index >= candidates.size()) {
            return fallback;
        }
        String value = candidates.get(index);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalizeNodeText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text
                .replaceAll("`", "")
                .replaceAll("^[-*#>\\d.\\s]+", "")
                .replaceAll("\\[[^]]+\\]\\([^)]+\\)", "")
                .replaceAll("\\s+", " ")
                .trim();

        if (normalized.length() < 2) {
            return "";
        }
        return limit(normalized, 20);
    }

    private void appendLine(StringBuilder svg, int x1, int y1, int x2, int y2, String color, double width) {
        svg.append("<line x1='").append(x1)
                .append("' y1='").append(y1)
                .append("' x2='").append(x2)
                .append("' y2='").append(y2)
                .append("' stroke='").append(color)
                .append("' stroke-width='").append(width)
                .append("'/>");
    }

    private void appendNode(
            StringBuilder svg,
            int x,
            int y,
            int w,
            int h,
            String text,
            int fontSize,
            String fill,
            String stroke,
            String fontColor) {
        svg.append("<rect x='").append(x)
                .append("' y='").append(y)
                .append("' width='").append(w)
                .append("' height='").append(h)
                .append("' rx='12' fill='").append(fill)
                .append("' stroke='").append(stroke)
                .append("' stroke-width='1.8'/>");
        appendText(svg, x + (w / 2), y + (h / 2) + 5, text, fontSize, fontColor, "middle");
    }

    private void appendText(StringBuilder svg, int x, int y, String text, int size, String color, String anchor) {
        svg.append("<text x='").append(x)
                .append("' y='").append(y)
                .append("' text-anchor='").append(anchor)
                .append("' fill='").append(color)
                .append("' font-size='").append(size)
                .append("' font-family='Arial'>")
                .append(text)
                .append("</text>");
    }

    private record MindBranch(String title, String leftLeaf, String rightLeaf) {
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
