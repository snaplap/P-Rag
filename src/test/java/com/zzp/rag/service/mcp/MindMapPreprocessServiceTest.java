package com.zzp.rag.service.mcp;

import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.model.RetrievalChunk;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class MindMapPreprocessServiceTest {

    @Test
    void shouldCompressAnswerAndRemoveUncertaintyPrefix() {
        RagProperties properties = new RagProperties();
        properties.getMcp().setMindMapMaxAnswerChars(40);

        MindMapPreprocessService service = new MindMapPreprocessService(properties);
        String compressed = service.compressAnswer("不确定性声明：当前证据不足，建议补充验证后再决策。这里是额外内容");

        Assertions.assertFalse(compressed.contains("不确定性声明"));
        Assertions.assertTrue(compressed.length() <= 40);
    }

    @Test
    void shouldPreprocessEvidenceWithDedupAndLimit() {
        RagProperties properties = new RagProperties();
        properties.getMcp().setMindMapMaxEvidenceItems(2);
        properties.getMcp().setMindMapSnippetChars(10);

        MindMapPreprocessService service = new MindMapPreprocessService(properties);
        List<RetrievalChunk> processed = service.preprocessEvidence(List.of(
                new RetrievalChunk("1", "doc-a", "同一段落内容同一段落内容", 0.9d, DataSourceType.KNOWLEDGE_BASE),
                new RetrievalChunk("2", "doc-a", "同一段落内容同一段落内容", 0.8d, DataSourceType.KNOWLEDGE_BASE),
                new RetrievalChunk("3", "doc-b", "另一个证据片段", 0.7d, DataSourceType.WEB)));

        Assertions.assertEquals(2, processed.size());
        Assertions.assertTrue(processed.get(0).content().length() <= 10);
    }

    @Test
    void shouldBuildEvidenceBindings() {
        RagProperties properties = new RagProperties();
        MindMapPreprocessService service = new MindMapPreprocessService(properties);

        List<Map<String, Object>> bindings = service.buildEvidenceBindings(List.of(
                new RetrievalChunk("1", "doc-a", "证据一", 0.9d, DataSourceType.KNOWLEDGE_BASE)));

        Assertions.assertEquals(1, bindings.size());
        Assertions.assertEquals("evidence-1", bindings.get(0).get("bindingId"));
        Assertions.assertEquals("doc-a", bindings.get(0).get("documentId"));
    }
}
