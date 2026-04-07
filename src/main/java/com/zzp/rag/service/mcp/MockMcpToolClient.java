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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
/**
 * MCP 客户端实现：支持真实 HTTP 调用，失败时自动回退 Mock 结果。
 */
public class MockMcpToolClient implements McpToolClient {

    private static final Logger log = LoggerFactory.getLogger(MockMcpToolClient.class);

    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, EndpointCircuitState> endpointStates = new ConcurrentHashMap<>();

    public MockMcpToolClient(RagProperties ragProperties, ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 联网搜索入口，优先真实 MCP，失败后回退本地模拟结果。
     */
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

    /**
     * 思维导图入口，优先真实 MCP，失败后返回可渲染的 SVG Data URL。
     */
    @Override
    public MindMapCommand generateMindMap(String question, String answer, DataSourceType sourceType,
            List<RetrievalChunk> evidence) {
        String fallbackReason = "mock-enabled";
        if (!ragProperties.getMcp().isUseMock()) {
            MindMapCommand remote = generateMindMapViaHttp(question, answer, sourceType, evidence);
            if (remote != null) {
                return remote;
            }
            fallbackReason = "diagram-call-failed";
        }
        return buildFallbackMindMapCommand(question, answer, sourceType, evidence, fallbackReason);
    }

    private MindMapCommand buildFallbackMindMapCommand(
            String question,
            String answer,
            DataSourceType sourceType,
            List<RetrievalChunk> evidence,
            String reason) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("topic", question);
        arguments.put("source", sourceType == null ? DataSourceType.KNOWLEDGE_BASE.name() : sourceType.name());
        arguments.put("summary", buildMindMapSummary(answer));
        arguments.put("evidenceCount", evidence == null ? 0 : evidence.size());
        arguments.put("mcpFallback", true);
        arguments.put("mcpFallbackReason", reason == null || reason.isBlank() ? "fallback" : reason);
        arguments.put("imageUrl", buildSvgMindMapDataUrl(question, answer, sourceType, evidence));
        return new MindMapCommand("mindmap.generate", arguments);
    }

    /**
     * 构建导图摘要，优先提取回答中的可读要点，避免把机制性文本透传到导图元数据。
     */
    private String buildMindMapSummary(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }

        List<String> points = collectMindMapHighlights(answer, 3);

        if (points.isEmpty()) {
            return buildFallbackSummary(answer);
        }
        return limit(String.join("；", points), 120);
    }

    private String buildFallbackSummary(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }

        List<String> sentences = new ArrayList<>();
        String[] parts = answer.split("[。！？!?\\n]");
        for (String part : parts) {
            String normalized = normalizeSummaryText(part);
            if (normalized.isBlank() || isMindMapNoise(normalized)) {
                continue;
            }
            sentences.add(normalized);
            if (sentences.size() >= 2) {
                break;
            }
        }

        if (!sentences.isEmpty()) {
            return limit(String.join("；", sentences), 80);
        }
        return limit(normalizeSummaryText(answer), 60);
    }

    /**
     * 真实 HTTP 联网搜索调用。
     */
    private List<WebSearchResult> searchWebViaHttp(String question, int topK) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", question);
            payload.put("topK", Math.max(1, topK));

            JsonCallResult callResult = postJsonWithAlternatives(
                    ragProperties.getMcp().getWebSearchUrl(),
                    payload,
                    buildWebSearchCandidateUrls(ragProperties.getMcp().getWebSearchUrl()),
                    "web-search");
            JsonNode root = callResult.root();
            JsonNode resultsNode = extractResultsNode(root);
            if (!resultsNode.isArray() || resultsNode.isEmpty()) {
                log.warn("MCP web search returned empty result set, endpoint={}", callResult.endpoint());
                return List.of();
            }

            List<WebSearchResult> results = new ArrayList<>();
            for (JsonNode node : resultsNode) {
                String title = firstText(node, "title", "name", "headline");
                String url = firstText(node, "url", "link", "href");
                String snippet = firstText(node, "snippet", "content", "summary", "text", "description");
                double confidence = firstDouble(node, 0.65d, "confidence", "score", "relevance", "rankScore");

                if (title.isBlank()) {
                    title = "WebResult";
                }
                results.add(new WebSearchResult(title, url, snippet, confidence));
            }
            log.info("MCP web search succeeded via endpoint={}, count={}", callResult.endpoint(), results.size());
            return results;
        } catch (Exception ex) {
            log.warn("MCP web search call failed, fallback to mock: {}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * 真实 HTTP 思维导图调用。
     */
    private MindMapCommand generateMindMapViaHttp(
            String question,
            String answer,
            DataSourceType sourceType,
            List<RetrievalChunk> evidence) {
        try {
            String sourceName = sourceType == null ? DataSourceType.KNOWLEDGE_BASE.name() : sourceType.name();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("topic", question);
            payload.put("summary", buildMindMapSummary(answer));
            payload.put("source", sourceName);
            payload.put("evidenceCount", evidence == null ? 0 : evidence.size());

            JsonCallResult callResult = postJsonWithAlternatives(
                    ragProperties.getMcp().getDiagramUrl(),
                    payload,
                    buildDiagramCandidateUrls(ragProperties.getMcp().getDiagramUrl()),
                    "diagram");
            JsonNode root = callResult.root();
            String tool = root.path("tool").asText("mindmap.generate");

            Map<String, Object> arguments = new LinkedHashMap<>();
            JsonNode argsNode = root.path("arguments");
            if (!argsNode.isObject()) {
                argsNode = root.path("data").path("arguments");
            }
            if (argsNode.isObject()) {
                arguments = objectMapper.convertValue(argsNode, Map.class);
            } else {
                arguments.put("topic", question);
                arguments.put("source", sourceName);
                arguments.put("summary", buildMindMapSummary(answer));
            }

            String imageUrl = firstText(root, "imageUrl", "diagramUrl", "url");
            if (imageUrl.isBlank()) {
                imageUrl = firstText(root.path("data"), "imageUrl", "diagramUrl", "url");
            }
            if (imageUrl.isBlank()) {
                imageUrl = firstTextFromMap(arguments, "imageUrl", "diagramUrl", "url");
            }

            String fallbackImageUrl = buildSvgMindMapDataUrl(question, answer, sourceType, evidence);
            String mermaidSource = extractMermaidSource(root, arguments, question, answer, sourceType, evidence);
            boolean upstreamFallbackImage = isLikelyUpstreamFallbackImage(imageUrl);
            boolean lowQualityTemplateImage = isLikelyLowQualityTemplateImage(imageUrl);
            boolean fallback = false;
            String fallbackReason = null;

            if (imageUrl.isBlank()) {
                String relayed = relayMermaidToDataUrl(mermaidSource);
                if (!relayed.isBlank()) {
                    arguments.put("imageUrl", relayed);
                    arguments.put("krokiRelayed", true);
                } else {
                    arguments.put("imageUrl", fallbackImageUrl);
                    fallback = true;
                    fallbackReason = "diagram-response-missing-image-url";
                }
            } else if (upstreamFallbackImage || lowQualityTemplateImage) {
                String relayed = relayMermaidToDataUrl(mermaidSource);
                if (!relayed.isBlank()) {
                    arguments.put("imageUrl", relayed);
                    arguments.put("upstreamImageUrl", imageUrl);
                    arguments.put("krokiRelayed", true);
                } else {
                    arguments.put("imageUrl", fallbackImageUrl);
                    arguments.put("upstreamImageUrl", imageUrl);
                    fallback = true;
                    fallbackReason = upstreamFallbackImage
                            ? "diagram-upstream-fallback-image"
                            : "diagram-upstream-low-quality-image";
                }
            } else if (!isBrowserRenderableImageUrl(imageUrl)) {
                String relayed = relayKrokiUrlToDataUrl(imageUrl);
                if (!relayed.isBlank()) {
                    arguments.put("imageUrl", relayed);
                    arguments.put("upstreamImageUrl", imageUrl);
                    arguments.put("krokiRelayed", true);
                } else {
                    arguments.put("imageUrl", fallbackImageUrl);
                    arguments.put("upstreamImageUrl", imageUrl);
                    fallback = true;
                    fallbackReason = "diagram-url-not-browser-reachable";
                    log.warn(
                            "MCP diagram returned non-browser-reachable imageUrl, fallback to local svg. endpoint={}, imageUrl={}",
                            callResult.endpoint(), imageUrl);
                }
            } else {
                arguments.put("imageUrl", imageUrl);
                if (!imageUrl.startsWith("data:image/")) {
                    // 给前端一个稳定的回退图源，避免远端图片地址偶发不可达时彻底不显示。
                    arguments.put("backupImageUrl", fallbackImageUrl);
                }
            }

            arguments.put("mcpFallback", fallback);
            if (fallbackReason != null) {
                arguments.put("mcpFallbackReason", fallbackReason);
            }
            arguments.put("mcpEndpoint", callResult.endpoint());
            log.info("MCP diagram succeeded via endpoint={}, hasImageUrl={}", callResult.endpoint(),
                    arguments.get("imageUrl") != null);
            return new MindMapCommand(tool, arguments);
        } catch (Exception ex) {
            log.warn("MCP diagram call failed, fallback to mock: {}", ex.getMessage());
            return null;
        }
    }

    private String relayKrokiUrlToDataUrl(String imageUrl) {
        if (!ragProperties.getMcp().isEnableKrokiRelay() || imageUrl == null || imageUrl.isBlank()) {
            return "";
        }

        try {
            String relayUrl = mapKrokiUrlToRelayBase(imageUrl);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(relayUrl))
                    .timeout(Duration.ofMillis(Math.max(1000, ragProperties.getMcp().getKrokiTimeoutMs())))
                    .header("Accept", "image/svg+xml,text/plain;q=0.9,*/*;q=0.8")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body() == null
                    || response.body().length == 0) {
                return "";
            }
            return toSvgDataUrl(response.body(), response.headers().firstValue("Content-Type").orElse(""));
        } catch (Exception ex) {
            log.warn("Kroki relay by URL failed: {}", ex.getMessage());
            return "";
        }
    }

    private String relayMermaidToDataUrl(String mermaidSource) {
        if (!ragProperties.getMcp().isEnableKrokiRelay() || mermaidSource == null || mermaidSource.isBlank()) {
            return "";
        }

        try {
            String krokiBaseUrl = ragProperties.getMcp().getKrokiBaseUrl();
            if (krokiBaseUrl == null || krokiBaseUrl.isBlank()) {
                return "";
            }

            String endpoint = krokiBaseUrl.endsWith("/")
                    ? krokiBaseUrl + "mermaid/svg"
                    : krokiBaseUrl + "/mermaid/svg";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMillis(Math.max(1000, ragProperties.getMcp().getKrokiTimeoutMs())))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .header("Accept", "image/svg+xml,text/plain;q=0.9,*/*;q=0.8")
                    .POST(HttpRequest.BodyPublishers.ofString(mermaidSource, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body() == null
                    || response.body().length == 0) {
                return "";
            }
            return toSvgDataUrl(response.body(), response.headers().firstValue("Content-Type").orElse(""));
        } catch (Exception ex) {
            log.warn("Kroki relay by Mermaid failed: {}", ex.getMessage());
            return "";
        }
    }

    private String mapKrokiUrlToRelayBase(String imageUrl) {
        URI original = URI.create(imageUrl);
        String host = original.getHost();
        if (host != null && !host.isBlank() && !"kroki".equalsIgnoreCase(host)) {
            return imageUrl;
        }

        String base = ragProperties.getMcp().getKrokiBaseUrl();
        if (base == null || base.isBlank()) {
            return imageUrl;
        }
        URI baseUri = URI.create(base);
        String path = original.getRawPath() == null ? "" : original.getRawPath();
        String query = original.getRawQuery();
        String mapped = baseUri.getScheme() + "://" + baseUri.getAuthority() + path;
        if (query != null && !query.isBlank()) {
            mapped += "?" + query;
        }
        return mapped;
    }

    private String toSvgDataUrl(byte[] payload, String contentType) {
        if (payload == null || payload.length == 0) {
            return "";
        }

        String bodyText = new String(payload, StandardCharsets.UTF_8).trim();
        if (!bodyText.startsWith("<svg") && !bodyText.contains("<svg")) {
            return "";
        }

        String mediaType = (contentType == null || contentType.isBlank()) ? "image/svg+xml" : contentType;
        int semicolon = mediaType.indexOf(';');
        if (semicolon > 0) {
            mediaType = mediaType.substring(0, semicolon);
        }
        if (!mediaType.startsWith("image/")) {
            mediaType = "image/svg+xml";
        }
        return "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(payload);
    }

    private boolean isLikelyUpstreamFallbackImage(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith("data:image/svg+xml;base64,")) {
            return false;
        }

        try {
            String base64 = imageUrl.substring("data:image/svg+xml;base64,".length());
            String decoded = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            return decoded.contains("diagram fallback");
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isLikelyLowQualityTemplateImage(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith("data:image/svg+xml;base64,")) {
            return false;
        }

        try {
            String base64 = imageUrl.substring("data:image/svg+xml;base64,".length());
            String decoded = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            boolean hasTemplateTokens = decoded.contains("summary:")
                    && decoded.contains("source:")
                    && decoded.contains("evidence:");
            boolean hasChineseTemplateTokens = decoded.contains("总结")
                    && decoded.contains("来源")
                    && decoded.contains("证据");
            return hasTemplateTokens || hasChineseTemplateTokens;
        } catch (Exception ex) {
            return false;
        }
    }

    private String extractMermaidSource(
            JsonNode root,
            Map<String, Object> arguments,
            String question,
            String answer,
            DataSourceType sourceType,
            List<RetrievalChunk> evidence) {
        String mermaid = firstText(root, "mermaid", "mermaidText", "diagramMermaid", "diagramSource");
        if (mermaid.isBlank()) {
            mermaid = firstText(root.path("data"), "mermaid", "mermaidText", "diagramMermaid", "diagramSource");
        }
        if (mermaid.isBlank()) {
            mermaid = firstTextFromMap(arguments, "mermaid", "mermaidText", "diagramMermaid", "diagramSource");
        }
        if (!mermaid.isBlank()) {
            return mermaid;
        }

        return buildMermaidMindMap(question, answer, sourceType, evidence);
    }

    private String buildMermaidMindMap(
            String question,
            String answer,
            DataSourceType sourceType,
            List<RetrievalChunk> evidence) {
        List<MindBranch> branches = buildMindMapBranches(answer);
        String root = sanitizeMermaidText(limit(question, 24));

        StringBuilder builder = new StringBuilder();
        builder.append("mindmap\n");
        builder.append("  root((").append(root).append("))\n");

        if (!branches.isEmpty()) {
            MindBranch first = branches.get(0);
            builder.append("    结论摘要\n");
            builder.append("      ").append(sanitizeMermaidText(limit(first.title(), 18))).append("\n");
            builder.append("        ").append(sanitizeMermaidText(limit(first.leftLeaf(), 22))).append("\n");
            builder.append("        ").append(sanitizeMermaidText(limit(first.rightLeaf(), 22))).append("\n");
        }
        if (branches.size() > 1) {
            MindBranch second = branches.get(1);
            builder.append("    优化建议\n");
            builder.append("      ").append(sanitizeMermaidText(limit(second.title(), 18))).append("\n");
            builder.append("        ").append(sanitizeMermaidText(limit(second.leftLeaf(), 22))).append("\n");
            builder.append("        ").append(sanitizeMermaidText(limit(second.rightLeaf(), 22))).append("\n");
        }
        if (branches.size() > 2) {
            MindBranch third = branches.get(2);
            builder.append("    执行动作\n");
            builder.append("      ").append(sanitizeMermaidText(limit(third.title(), 18))).append("\n");
            builder.append("        ").append(sanitizeMermaidText(limit(third.leftLeaf(), 22))).append("\n");
            builder.append("        ").append(sanitizeMermaidText(limit(third.rightLeaf(), 22))).append("\n");
        }
        return builder.toString();
    }

    private String sanitizeMermaidText(String text) {
        if (text == null || text.isBlank()) {
            return "-";
        }
        String normalized = text.replace('\n', ' ')
                .replace('\r', ' ')
                .replace('(', ' ')
                .replace(')', ' ')
                .replace('[', ' ')
                .replace(']', ' ')
                .replace('{', ' ')
                .replace('}', ' ')
                .replace('"', ' ')
                .replace("`", " ")
                .trim();
        return normalized.isBlank() ? "-" : normalized;
    }

    /**
     * 候选端点重试调用。
     */
    private JsonCallResult postJsonWithAlternatives(
            String preferredUrl,
            Map<String, Object> payload,
            List<String> candidateUrls,
            String feature) throws Exception {
        Exception last = null;
        for (String url : candidateUrls) {
            if (isCircuitOpen(feature, url)) {
                log.warn("MCP {} endpoint skipped by circuit breaker: {}", feature, url);
                continue;
            }

            try {
                JsonNode root = postJsonWithRetry(url, payload, feature);
                recordSuccess(feature, url);
                log.info("MCP {} endpoint succeeded: {}", feature, url);
                return new JsonCallResult(url, root);
            } catch (Exception ex) {
                recordFailure(feature, url);
                last = ex;
                log.warn("MCP {} endpoint failed: {}, reason={}", feature, url, ex.getMessage());
            }
        }

        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("No available endpoint for " + feature + ", preferred=" + preferredUrl);
    }

    private JsonNode postJsonWithRetry(String url, Map<String, Object> payload, String feature) throws Exception {
        int maxRetries = Math.max(0, ragProperties.getMcp().getMaxRetries());
        int backoffMs = Math.max(0, ragProperties.getMcp().getRetryBackoffMs());

        Exception last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return postJson(url, payload, feature);
            } catch (Exception ex) {
                last = ex;
                if (attempt >= maxRetries || !isRetryable(ex)) {
                    throw ex;
                }
                if (backoffMs > 0) {
                    long sleepMs = (long) backoffMs * (attempt + 1L);
                    Thread.sleep(sleepMs);
                }
            }
        }

        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("MCP request failed without exception: " + url);
    }

    private boolean isRetryable(Exception ex) {
        String message = ex == null || ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("timed out") || message.contains("timeout") || message.contains("connection")) {
            return true;
        }
        if (message.contains("status=")) {
            int status = parseStatusCode(message);
            return status == 429 || status >= 500;
        }
        return false;
    }

    private int parseStatusCode(String message) {
        int idx = message.indexOf("status=");
        if (idx < 0) {
            return -1;
        }

        int start = idx + "status=".length();
        int end = start;
        while (end < message.length() && Character.isDigit(message.charAt(end))) {
            end++;
        }
        if (end <= start) {
            return -1;
        }
        try {
            return Integer.parseInt(message.substring(start, end));
        } catch (Exception ignored) {
            return -1;
        }
    }

    /**
     * 判断图片 URL 是否可被浏览器直接渲染。
     */
    private boolean isBrowserRenderableImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return false;
        }

        String normalized = imageUrl.trim();
        if (normalized.startsWith("data:image/")) {
            return true;
        }
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            return false;
        }

        try {
            URI uri = URI.create(normalized);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            // kroki 通常是容器内服务名，浏览器端不可直连。
            return !"kroki".equalsIgnoreCase(host);
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isCircuitOpen(String feature, String url) {
        EndpointCircuitState state = endpointStates.get(buildEndpointStateKey(feature, url));
        if (state == null) {
            return false;
        }
        return state.openUntilMs > System.currentTimeMillis();
    }

    private void recordFailure(String feature, String url) {
        String key = buildEndpointStateKey(feature, url);
        EndpointCircuitState state = endpointStates.computeIfAbsent(key, ignored -> new EndpointCircuitState());

        synchronized (state) {
            state.consecutiveFailures++;
            int threshold = Math.max(1, ragProperties.getMcp().getCircuitBreakerThreshold());
            if (state.consecutiveFailures >= threshold) {
                int openMs = Math.max(1000, ragProperties.getMcp().getCircuitBreakerOpenMs());
                state.openUntilMs = System.currentTimeMillis() + openMs;
                state.consecutiveFailures = 0;
                log.warn("MCP {} circuit opened for endpoint={}, openMs={}", feature, url, openMs);
            }
        }
    }

    private void recordSuccess(String feature, String url) {
        EndpointCircuitState state = endpointStates.get(buildEndpointStateKey(feature, url));
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.consecutiveFailures = 0;
            state.openUntilMs = 0L;
        }
    }

    private String buildEndpointStateKey(String feature, String url) {
        return feature + "|" + url;
    }

    /**
     * 执行单次 HTTP JSON POST。
     */
    private JsonNode postJson(String url, Map<String, Object> payload, String feature) throws Exception {
        String body = objectMapper.writeValueAsString(payload);
        String requestId = UUID.randomUUID().toString();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1000, ragProperties.getMcp().getCallTimeoutMs())))
                .header("Content-Type", "application/json")
                .header("X-Request-Id", requestId)
                .header("X-MCP-Feature", feature)
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

    /**
     * 兼容多种返回结构，提取搜索结果数组节点。
     */
    private JsonNode extractResultsNode(JsonNode root) {
        if (root == null || root.isNull()) {
            return objectMapper.createArrayNode();
        }
        if (root.isArray()) {
            return root;
        }

        JsonNode results = root.path("results");
        if (results.isArray()) {
            return results;
        }

        JsonNode items = root.path("items");
        if (items.isArray()) {
            return items;
        }

        JsonNode data = root.path("data");
        if (data.isArray()) {
            return data;
        }
        JsonNode dataResults = data.path("results");
        if (dataResults.isArray()) {
            return dataResults;
        }
        JsonNode dataItems = data.path("items");
        if (dataItems.isArray()) {
            return dataItems;
        }
        return objectMapper.createArrayNode();
    }

    /**
     * 按候选字段顺序读取第一个非空文本。
     */
    private String firstText(JsonNode node, String... fields) {
        if (node == null || node.isNull()) {
            return "";
        }
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String firstTextFromMap(Map<String, Object> map, String... fields) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        for (String field : fields) {
            Object value = map.get(field);
            if (value instanceof String text && !text.isBlank()) {
                return text.trim();
            }
        }
        return "";
    }

    /**
     * 按候选字段顺序读取第一个可解析数值。
     */
    private double firstDouble(JsonNode node, double defaultValue, String... fields) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        for (String field : fields) {
            JsonNode valueNode = node.path(field);
            if (!valueNode.isMissingNode() && !valueNode.isNull()) {
                try {
                    return Double.parseDouble(valueNode.asText());
                } catch (Exception ignored) {
                    // continue
                }
            }
        }
        return defaultValue;
    }

    /**
     * 构建联网搜索候选端点列表。
     */
    private List<String> buildWebSearchCandidateUrls(String primaryUrl) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(primaryUrl);
        candidates.add(replaceLastPath(primaryUrl, "/search"));
        candidates.add(replaceLastPath(primaryUrl, "/mcp/search"));
        candidates.add(replaceLastPath(primaryUrl, "/mcp/web-search/search"));
        return candidates.stream().filter(v -> v != null && !v.isBlank()).toList();
    }

    /**
     * 构建导图候选端点列表。
     */
    private List<String> buildDiagramCandidateUrls(String primaryUrl) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(primaryUrl);
        candidates.add(replaceLastPath(primaryUrl, "/diagram"));
        candidates.add(replaceLastPath(primaryUrl, "/mcp/diagram"));
        candidates.add(replaceLastPath(primaryUrl, "/mindmap"));
        return candidates.stream().filter(v -> v != null && !v.isBlank()).toList();
    }

    /**
     * 替换 URL 路径段。
     */
    private String replaceLastPath(String url, String newPath) {
        if (url == null || url.isBlank()) {
            return url;
        }
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getAuthority() + newPath;
        } catch (Exception ex) {
            return url;
        }
    }

    /**
     * 生成思维导图 SVG 并转成 data URL。
     */
    private String buildSvgMindMapDataUrl(
            String question,
            String answer,
            DataSourceType sourceType,
            List<RetrievalChunk> evidence) {
        String topic = xmlEscape(limit(question, 20));
        List<MindBranch> branches = buildMindMapBranches(answer);

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
        appendText(svg, 36, 548, "结论导图", 14, "#356789", "start");

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

    /**
     * 从回答中提取导图分支。
     */
    private List<MindBranch> buildMindMapBranches(String answer) {
        List<String> candidates = collectMindMapHighlights(answer, 12);

        Map<String, String> uniqueByKey = new LinkedHashMap<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String normalized = candidate.replace("\u0000", "").trim();
            if (normalized.isBlank()) {
                continue;
            }
            uniqueByKey.putIfAbsent(normalized.toLowerCase(Locale.ROOT), normalized);
        }

        List<String> readable = new ArrayList<>();
        for (String item : uniqueByKey.values()) {
            if (item == null || item.isBlank()) {
                continue;
            }
            readable.add(item);
            if (readable.size() >= 12) {
                break;
            }
        }

        if (readable.isEmpty()) {
            readable = List.of("关键结论", "可执行建议", "优先动作", "方案要点", "风险提醒", "落地路径");
        }

        List<MindBranch> branches = new ArrayList<>();
        branches.add(new MindBranch(
                pick(readable, 0, "关键结论"),
                pick(readable, 3, "结论补充"),
                pick(readable, 4, "结论依据")));
        branches.add(new MindBranch(
                pick(readable, 1, "可执行建议"),
                pick(readable, 5, "优先顺序"),
                pick(readable, 6, "实施要点")));
        branches.add(new MindBranch(
                pick(readable, 2, "优先动作"),
                pick(readable, 7, "风险提醒"),
                pick(readable, 8, "后续行动")));
        return branches;
    }

    private List<String> collectMindMapHighlights(String answer, int maxItems) {
        if (answer == null || answer.isBlank() || maxItems <= 0) {
            return List.of();
        }

        List<HighlightCandidate> ranked = new ArrayList<>();
        int order = 0;
        for (String piece : splitAnswerPieces(answer)) {
            String normalized = normalizeNodeText(piece);
            if (normalized.isBlank() || isMindMapNoise(normalized)) {
                continue;
            }
            ranked.add(new HighlightCandidate(normalized, scoreCandidate(normalized), order++));
        }

        ranked.sort(Comparator
                .comparingInt(HighlightCandidate::score).reversed()
                .thenComparingInt(HighlightCandidate::order));

        Map<String, String> dedup = new LinkedHashMap<>();
        for (HighlightCandidate candidate : ranked) {
            dedup.putIfAbsent(candidate.text().toLowerCase(Locale.ROOT), candidate.text());
            if (dedup.size() >= maxItems) {
                break;
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private int scoreCandidate(String text) {
        int score = 0;
        String lower = text.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "结论", "建议", "应", "需要", "优先", "推荐", "方案", "措施", "行动", "下一步", "改进", "优化", "提升", "避免")) {
            score += 8;
        }
        if (containsAny(lower, "因此", "所以", "综上", "可", "应当", "建议先")) {
            score += 4;
        }
        if (text.length() >= 6 && text.length() <= 18) {
            score += 2;
        }
        return score;
    }

    /**
     * 安全读取候选文本。
     */
    private String pick(List<String> candidates, int index, String fallback) {
        if (candidates == null || index < 0 || index >= candidates.size()) {
            return fallback;
        }
        String value = candidates.get(index);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 文本归一化，供导图节点使用。
     */
    private String normalizeNodeText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text
                .replaceAll("`", "")
                .replaceAll("^[-*#>\\d.\\s]+", "")
                .replaceAll("!\\[[^]]*\\]\\([^)]+\\)", "")
                .replaceAll("\\[[^]]+\\]\\([^)]+\\)", "")
                .replaceAll("\\s+", " ")
                .trim();

        if (normalized.length() < 2) {
            return "";
        }
        return limit(normalized, 20);
    }

    private String normalizeSummaryText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text
                .replaceAll("`", "")
                .replaceAll("^[-*#>\\d.\\s]+", "")
                .replaceAll("!\\[[^]]*\\]\\([^)]+\\)", "")
                .replaceAll("\\[[^]]+\\]\\([^)]+\\)", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.length() < 4) {
            return "";
        }
        return limit(normalized, 48);
    }

    private List<String> splitAnswerPieces(String answer) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        List<String> pieces = new ArrayList<>();
        String normalizedAnswer = answer
                .replaceAll("(?m)^\\s*[-*]\\s+", "\n")
                .replaceAll("(?m)^\\s*\\d+[.)]\\s+", "\n");
        String[] lines = normalizedAnswer.split("\\r?\\n");
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] segments = line.split("[。；;！？!?]");
            for (String segment : segments) {
                if (segment != null && !segment.isBlank()) {
                    pieces.add(segment);
                }
            }
        }
        if (pieces.isEmpty()) {
            pieces.add(answer);
        }
        return pieces;
    }

    private boolean isMindMapNoise(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("summary")
                || lower.contains("source")
                || lower.contains("evidence")
                || lower.contains("mocksearch")
                || lower.contains("mcp")
                || text.contains("来源")
                || text.contains("证据")
                || text.contains("向量检索")
                || text.contains("机制")
                || text.contains("基于")
                || text.contains("根据")
                || text.contains("检索")
                || text.contains("排序");
    }

    private boolean containsAny(String text, String... words) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String word : words) {
            if (word != null && !word.isBlank() && text.contains(word)) {
                return true;
            }
        }
        return false;
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

    private record HighlightCandidate(String text, int score, int order) {
    }

    private record JsonCallResult(String endpoint, JsonNode root) {
    }

    private static final class EndpointCircuitState {
        private int consecutiveFailures;
        private long openUntilMs;
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
