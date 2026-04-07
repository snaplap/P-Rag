package com.zzp.rag.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.dto.MindMapCommand;
import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.model.RetrievalChunk;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

class MockMcpToolClientTest {

    @Test
    void shouldMarkMindMapAsFallbackWhenMockEnabled() {
        RagProperties properties = new RagProperties();
        properties.getMcp().setUseMock(true);

        MockMcpToolClient client = new MockMcpToolClient(properties, new ObjectMapper());
        MindMapCommand command = client.generateMindMap(
                "测试主题",
                "测试答案",
                DataSourceType.KNOWLEDGE_BASE,
                java.util.List.of());

        Assertions.assertNotNull(command);
        Assertions.assertNotNull(command.arguments());
        Assertions.assertEquals(Boolean.TRUE, command.arguments().get("mcpFallback"));
        Assertions.assertEquals("mock-enabled", command.arguments().get("mcpFallbackReason"));

        Object imageUrl = command.arguments().get("imageUrl");
        Assertions.assertTrue(imageUrl instanceof String);
        Assertions.assertTrue(((String) imageUrl).startsWith("data:image/svg+xml;base64,"));
    }

    @Test
    void shouldBuildFallbackMindMapFromAnswerOnly() {
        RagProperties properties = new RagProperties();
        properties.getMcp().setUseMock(true);

        MockMcpToolClient client = new MockMcpToolClient(properties, new ObjectMapper());
        MindMapCommand command = client.generateMindMap(
                "测试主题",
                "核心结论一\n核心结论二",
                DataSourceType.KNOWLEDGE_BASE,
                java.util.List.of(
                        new RetrievalChunk("1", "doc-a", "证据原文XYZ不应进入导图", 0.95d,
                                DataSourceType.KNOWLEDGE_BASE)));

        Assertions.assertNotNull(command);
        Map<String, Object> arguments = command.arguments();
        Assertions.assertNotNull(arguments);
        Assertions.assertTrue(String.valueOf(arguments.get("summary")).contains("核心结论一"));
        Assertions.assertFalse(String.valueOf(arguments.get("summary")).contains("证据原文XYZ"));

        String imageUrl = String.valueOf(arguments.get("imageUrl"));
        String svg = new String(
                Base64.getDecoder().decode(imageUrl.substring("data:image/svg+xml;base64,".length())),
                StandardCharsets.UTF_8);
        Assertions.assertTrue(svg.contains("核心结论一") || svg.contains("核心结论二"));
        Assertions.assertFalse(svg.contains("证据原文XYZ"));
    }

    @Test
    void shouldFilterNoiseFromMindMapSummary() {
        RagProperties properties = new RagProperties();
        properties.getMcp().setUseMock(true);

        MockMcpToolClient client = new MockMcpToolClient(properties, new ObjectMapper());
        MindMapCommand command = client.generateMindMap(
                "主题",
                "来源: WEB\n证据: 3条\n建议先完成数据治理",
                DataSourceType.HYBRID,
                java.util.List.of());

        String summary = String.valueOf(command.arguments().get("summary"));
        Assertions.assertFalse(summary.contains("来源"));
        Assertions.assertFalse(summary.contains("证据"));
        Assertions.assertTrue(summary.contains("建议先完成数据治理"));
    }

        @Test
        void shouldGenerateConclusionFirstSummaryFromLongParagraph() {
        RagProperties properties = new RagProperties();
        properties.getMcp().setUseMock(true);

        MockMcpToolClient client = new MockMcpToolClient(properties, new ObjectMapper());
        String answer = "根据向量检索结果与排序机制，当前流程存在噪声。"
            + "结论是先完成数据清理并统一字段口径。"
            + "建议优先修复低质量文档再扩展召回范围。";
        MindMapCommand command = client.generateMindMap(
            "主题",
            answer,
            DataSourceType.HYBRID,
            java.util.List.of());

        String summary = String.valueOf(command.arguments().get("summary"));
        Assertions.assertTrue(summary.contains("结论") || summary.contains("建议"));
        Assertions.assertFalse(summary.contains("向量检索"));
        Assertions.assertFalse(summary.contains("排序机制"));
        Assertions.assertTrue(summary.length() <= 120);
        }

        @Test
        void shouldExtractSummaryFromListAnswer() {
        RagProperties properties = new RagProperties();
        properties.getMcp().setUseMock(true);

        MockMcpToolClient client = new MockMcpToolClient(properties, new ObjectMapper());
        String answer = "1. 结论：需要先修正文档结构\n"
            + "2. 建议：按优先级处理历史数据\n"
            + "3. 行动：本周完成清洗任务";

        MindMapCommand command = client.generateMindMap(
            "主题",
            answer,
            DataSourceType.KNOWLEDGE_BASE,
            java.util.List.of());

        String summary = String.valueOf(command.arguments().get("summary"));
        Assertions.assertTrue(summary.contains("结论") || summary.contains("建议") || summary.contains("行动"));
        Assertions.assertFalse(summary.contains("1."));
        }

    @Test
    void shouldMarkMindMapAsFallbackWhenRemoteDiagramCallFails() {
        RagProperties properties = new RagProperties();
        properties.getMcp().setUseMock(false);
        properties.getMcp().setDiagramUrl("http://127.0.0.1:9/mcp/diagram");
        properties.getMcp().setCallTimeoutMs(300);
        properties.getMcp().setMaxRetries(0);

        MockMcpToolClient client = new MockMcpToolClient(properties, new ObjectMapper());
        MindMapCommand command = client.generateMindMap(
                "测试主题",
                "测试答案",
                DataSourceType.HYBRID,
                java.util.List.of());

        Assertions.assertNotNull(command);
        Map<String, Object> arguments = command.arguments();
        Assertions.assertNotNull(arguments);
        Assertions.assertEquals(Boolean.TRUE, arguments.get("mcpFallback"));
        Assertions.assertEquals("diagram-call-failed", arguments.get("mcpFallbackReason"));

        Object imageUrl = arguments.get("imageUrl");
        Assertions.assertTrue(imageUrl instanceof String);
        Assertions.assertTrue(((String) imageUrl).startsWith("data:image/svg+xml;base64,"));
    }

    @Test
    void shouldFallbackWhenRemoteImageUrlIsNotBrowserReachable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp/diagram", exchange -> {
            String body = """
                    {
                      "tool": "mindmap.generate",
                      "arguments": {
                        "imageUrl": "http://kroki:8000/mermaid/svg/demo"
                      }
                    }
                    """;
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(payload);
            }
        });
        server.start();

        try {
            RagProperties properties = new RagProperties();
            properties.getMcp().setUseMock(false);
            properties.getMcp().setEnableKrokiRelay(false);
            properties.getMcp().setCallTimeoutMs(1000);
            properties.getMcp().setMaxRetries(0);
            properties.getMcp().setDiagramUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp/diagram");

            MockMcpToolClient client = new MockMcpToolClient(properties, new ObjectMapper());
            MindMapCommand command = client.generateMindMap(
                    "主题",
                    "回答",
                    DataSourceType.HYBRID,
                    java.util.List.of());

            Assertions.assertNotNull(command);
            Assertions.assertNotNull(command.arguments());
            Assertions.assertEquals(Boolean.TRUE, command.arguments().get("mcpFallback"));
            Assertions.assertEquals("diagram-url-not-browser-reachable", command.arguments().get("mcpFallbackReason"));
            Assertions.assertEquals("http://kroki:8000/mermaid/svg/demo", command.arguments().get("upstreamImageUrl"));

            Object imageUrl = command.arguments().get("imageUrl");
            Assertions.assertTrue(imageUrl instanceof String);
            Assertions.assertTrue(((String) imageUrl).startsWith("data:image/svg+xml;base64,"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRelayKrokiImageUrlWhenRelayIsEnabled() throws Exception {
        HttpServer krokiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        krokiServer.createContext("/mermaid/svg/demo", exchange -> {
            byte[] payload = "<svg xmlns='http://www.w3.org/2000/svg'><text x='10' y='20'>ok</text></svg>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "image/svg+xml");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(payload);
            }
        });
        krokiServer.start();

        HttpServer diagramServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        diagramServer.createContext("/mcp/diagram", exchange -> {
            String body = """
                    {
                      "tool": "mindmap.generate",
                      "arguments": {
                        "imageUrl": "http://kroki:8000/mermaid/svg/demo"
                      }
                    }
                    """;
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(payload);
            }
        });
        diagramServer.start();

        try {
            RagProperties properties = new RagProperties();
            properties.getMcp().setUseMock(false);
            properties.getMcp().setEnableKrokiRelay(true);
            properties.getMcp().setKrokiBaseUrl("http://127.0.0.1:" + krokiServer.getAddress().getPort());
            properties.getMcp().setKrokiTimeoutMs(1000);
            properties.getMcp().setCallTimeoutMs(1000);
            properties.getMcp().setMaxRetries(0);
            properties.getMcp()
                    .setDiagramUrl("http://127.0.0.1:" + diagramServer.getAddress().getPort() + "/mcp/diagram");

            MockMcpToolClient client = new MockMcpToolClient(properties, new ObjectMapper());
            MindMapCommand command = client.generateMindMap(
                    "主题",
                    "回答",
                    DataSourceType.HYBRID,
                    java.util.List.of());

            Assertions.assertNotNull(command);
            Assertions.assertNotNull(command.arguments());
            Assertions.assertEquals(Boolean.FALSE, command.arguments().get("mcpFallback"));
            Assertions.assertEquals(Boolean.TRUE, command.arguments().get("krokiRelayed"));
            Assertions.assertEquals("http://kroki:8000/mermaid/svg/demo", command.arguments().get("upstreamImageUrl"));

            Object imageUrl = command.arguments().get("imageUrl");
            Assertions.assertTrue(imageUrl instanceof String);
            Assertions.assertTrue(((String) imageUrl).startsWith("data:image/svg+xml;base64,"));
        } finally {
            diagramServer.stop(0);
            krokiServer.stop(0);
        }
    }

    @Test
    void shouldRelayWhenUpstreamReturnsLowQualityTemplateImage() throws Exception {
        String lowQualitySvg = "<svg xmlns='http://www.w3.org/2000/svg'><text>Summary: xxx</text><text>Source: WEB</text><text>Evidence: 3</text></svg>";
        String lowQualityDataUrl = "data:image/svg+xml;base64,"
                + Base64.getEncoder().encodeToString(lowQualitySvg.getBytes(StandardCharsets.UTF_8));

        HttpServer krokiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        krokiServer.createContext("/mermaid/svg", exchange -> {
            byte[] payload = "<svg xmlns='http://www.w3.org/2000/svg'><text x='10' y='20'>mindmap ok</text></svg>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "image/svg+xml");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(payload);
            }
        });
        krokiServer.start();

        HttpServer diagramServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        diagramServer.createContext("/mcp/diagram", exchange -> {
            String body = """
                    {
                      "tool": "mindmap.generate",
                      "arguments": {
                        "imageUrl": "%s"
                      }
                    }
                    """.formatted(lowQualityDataUrl.replace("\"", "\\\""));
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(payload);
            }
        });
        diagramServer.start();

        try {
            RagProperties properties = new RagProperties();
            properties.getMcp().setUseMock(false);
            properties.getMcp().setEnableKrokiRelay(true);
            properties.getMcp().setKrokiBaseUrl("http://127.0.0.1:" + krokiServer.getAddress().getPort());
            properties.getMcp().setKrokiTimeoutMs(1000);
            properties.getMcp().setCallTimeoutMs(1000);
            properties.getMcp().setMaxRetries(0);
            properties.getMcp()
                    .setDiagramUrl("http://127.0.0.1:" + diagramServer.getAddress().getPort() + "/mcp/diagram");

            MockMcpToolClient client = new MockMcpToolClient(properties, new ObjectMapper());
            MindMapCommand command = client.generateMindMap(
                    "主题",
                    "回答",
                    DataSourceType.WEB,
                    java.util.List.of());

            Assertions.assertNotNull(command);
            Assertions.assertEquals(Boolean.FALSE, command.arguments().get("mcpFallback"));
            Assertions.assertEquals(Boolean.TRUE, command.arguments().get("krokiRelayed"));
            Assertions.assertEquals(lowQualityDataUrl, command.arguments().get("upstreamImageUrl"));
            Assertions.assertTrue(
                    String.valueOf(command.arguments().get("imageUrl")).startsWith("data:image/svg+xml;base64,"));
        } finally {
            diagramServer.stop(0);
            krokiServer.stop(0);
        }
    }
}
