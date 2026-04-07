package com.zzp.rag.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.dto.MindMapCommand;
import com.zzp.rag.domain.model.DataSourceType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
}
