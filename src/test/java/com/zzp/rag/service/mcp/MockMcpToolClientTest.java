package com.zzp.rag.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.dto.MindMapCommand;
import com.zzp.rag.domain.model.DataSourceType;
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
            Assertions.assertTrue(String.valueOf(command.arguments().get("imageUrl")).startsWith("data:image/svg+xml;base64,"));
        } finally {
            diagramServer.stop(0);
            krokiServer.stop(0);
        }
    }
}
